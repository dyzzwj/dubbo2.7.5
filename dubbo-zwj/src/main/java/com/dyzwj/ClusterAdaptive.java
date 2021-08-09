package com.dyzwj;

import org.apache.dubbo.common.extension.ExtensionLoader;

public class ClusterAdaptive {

    public class Cluster$Adaptive implements org.apache.dubbo.rpc.cluster.Cluster {
        public org.apache.dubbo.rpc.Invoker join(org.apache.dubbo.rpc.cluster.Directory arg0) throws org.apache.dubbo.rpc.RpcException {
            if (arg0 == null) throw new IllegalArgumentException("org.apache.dubbo.rpc.cluster.Directory argument == null");
            if (arg0.getUrl() == null) throw new IllegalArgumentException("org.apache.dubbo.rpc.cluster.Directory argument getUrl() == null");
            org.apache.dubbo.common.URL url = arg0.getUrl();
            String extName = url.getParameter("cluster", "failover");
            if(extName == null) throw new IllegalStateException("Failed to get extension (org.apache.dubbo.rpc.cluster.Cluster) name from url (" + url.toString() + ") use keys([cluster])");
            /**
             * 在找扩展点的过程中 如果有wrapper类 会进行包装
             */
            org.apache.dubbo.rpc.cluster.Cluster extension = (org.apache.dubbo.rpc.cluster.Cluster) ExtensionLoader.getExtensionLoader(org.apache.dubbo.rpc.cluster.Cluster.class).getExtension(extName);
            return extension.join(arg0);
        }
    }
}
