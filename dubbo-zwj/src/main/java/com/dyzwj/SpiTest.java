package com.dyzwj;

import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.cluster.RouterFactory;

public class SpiTest {

    public static void main(String[] args) {

//        ExtensionLoader<Protocol> extensionLoader = ExtensionLoader.getExtensionLoader(Protocol.class);
//        Protocol http = extensionLoader.getExtension("http");
//        System.out.println(http);


        ExtensionLoader.getExtensionLoader(RouterFactory.class).getAdaptiveExtension();


    }
}
