/*
 * Copyright 1999-2011 Alibaba Group.
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.rpc.cluster.support;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.RpcResult;
import com.alibaba.dubbo.rpc.cluster.Directory;
import com.alibaba.dubbo.rpc.cluster.Merger;
import com.alibaba.dubbo.rpc.cluster.merger.MergerFactory;

/**
 * @author <a href="mailto:gang.lvg@alibaba-inc.com">kimi</a>
 */
@SuppressWarnings( "unchecked" )
public class MergeableClusterInvoker<T> implements Invoker<T> {

    private static final Logger log = LoggerFactory.getLogger(MergeableClusterInvoker.class);

    private ExecutorService executor = Executors.newCachedThreadPool(new NamedThreadFactory("mergeable-cluster-executor", true));
    
    private final Directory<T> directory;

    public MergeableClusterInvoker(Directory<T> directory) {
        this.directory = directory;
    }

    @SuppressWarnings("rawtypes")
	public Result invoke(final Invocation invocation) throws RpcException {
        List<Invoker<T>> invokers = directory.list(invocation);
        
        String merger = getUrl().getMethodParameter( invocation.getMethodName(), Constants.MERGER_KEY );
        if ( ConfigUtils.isEmpty(merger) ) { // ?????????????????????Merge????????????????????????Group
            for(final Invoker<T> invoker : invokers ) {
                if (invoker.isAvailable()) {
                    return invoker.invoke(invocation);
                }
            }
            return invokers.iterator().next().invoke(invocation);
        }
        
        Class<?> returnType;
        try {
            returnType = getInterface().getMethod(
                    invocation.getMethodName(), invocation.getParameterTypes() ).getReturnType();
        } catch ( NoSuchMethodException e ) {
            returnType = null;
        }
        
        Map<String, Future<Result>> results = new HashMap<String, Future<Result>>();
        for( final Invoker<T> invoker : invokers ) {
            Future<Result> future = executor.submit( new Callable<Result>() {
                public Result call() throws Exception {
                    return invoker.invoke(new RpcInvocation(invocation, invoker));
                }
            } );
            results.put( invoker.getUrl().getServiceKey(), future );
        }

        Object result = null;
        
        List<Result> resultList = new ArrayList<Result>( results.size() );
        
        int timeout = getUrl().getMethodParameter( invocation.getMethodName(), Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT );
        for ( Map.Entry<String, Future<Result>> entry : results.entrySet() ) {
            Future<Result> future = entry.getValue();
            try {
                Result r = future.get(timeout, TimeUnit.MILLISECONDS);
                if (r.hasException()) {
                    log.error(new StringBuilder(32).append("Invoke ")
                                  .append(getGroupDescFromServiceKey(entry.getKey()))
                                  .append(" failed: ")
                                  .append(r.getException().getMessage()).toString(),
                              r.getException());
                } else {
                    resultList.add(r);
                }
            } catch ( Exception e ) {
                throw new RpcException( new StringBuilder( 32 )
                                                .append( "Failed to invoke service " )
                                                .append( entry.getKey() )
                                                .append( ": " )
                                                .append( e.getMessage() ).toString(),
                                        e );
            }
        }
        
        if (resultList.size() == 0) {
            return new RpcResult((Object)null);
        } else if (resultList.size() == 1) {
            return resultList.iterator().next();
        }

        if (returnType == void.class) {
            return new RpcResult((Object)null);
        }

        if ( merger.startsWith(".") ) {
            merger = merger.substring(1);
            Method method;
            try {
                method = returnType.getMethod( merger, returnType );
            } catch ( NoSuchMethodException e ) {
                throw new RpcException( new StringBuilder( 32 )
                                                .append( "Can not merge result because missing method [ " )
                                                .append( merger )
                                                .append( " ] in class [ " )
                                                .append( returnType.getClass().getName() )
                                                .append( " ]" )
                                                .toString() );
            }
            if ( method != null ) {
                if ( !Modifier.isPublic( method.getModifiers() ) ) {
                    method.setAccessible( true );
                }
                result = resultList.remove( 0 ).getValue();
                try {
                    if ( method.getReturnType() != void.class
                            && method.getReturnType().isAssignableFrom( result.getClass() ) ) {
                        for ( Result r : resultList ) {
                            result = method.invoke( result, r.getValue() );
                        }
                    } else {
                        for ( Result r : resultList ) {
                            method.invoke( result, r.getValue() );
                        }
                    }
                } catch ( Exception e ) {
                    throw new RpcException( 
                            new StringBuilder( 32 )
                                    .append( "Can not merge result: " )
                                    .append( e.getMessage() ).toString(), 
                            e );
                }
            } else {
                throw new RpcException(
                        new StringBuilder( 32 )
                                .append( "Can not merge result because missing method [ " )
                                .append( merger )
                                .append( " ] in class [ " )
                                .append( returnType.getClass().getName() )
                                .append( " ]" )
                                .toString() );
            }
        } else {
            Merger resultMerger;
            if (ConfigUtils.isDefault(merger)) {
                resultMerger = MergerFactory.getMerger(returnType);
            } else {
                resultMerger = ExtensionLoader.getExtensionLoader(Merger.class).getExtension(merger);
            }
            if (resultMerger != null) {
                List<Object> rets = new ArrayList<Object>(resultList.size());
                for(Result r : resultList) {
                    rets.add(r.getValue());
                }
                result = resultMerger.merge(
                        rets.toArray((Object[])Array.newInstance(returnType, 0)));
            } else {
                throw new RpcException( "There is no merger to merge result." );
            }
        }
        return new RpcResult( result );
    }

    public Class<T> getInterface() {
        return directory.getInterface();
    }

    public URL getUrl() {
        return directory.getUrl();
    }

    public boolean isAvailable() {
        return directory.isAvailable();
    }

    public void destroy() {
        directory.destroy();
    }

    private String getGroupDescFromServiceKey(String key) {
        int index = key.indexOf("/");
        if (index > 0) {
            return new StringBuilder(32).append("group [ ")
                .append(key.substring(0, index)).append(" ]").toString();
        }
        return key;
    }
}
