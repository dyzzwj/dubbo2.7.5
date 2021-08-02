package com.dyzwj;

import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.rpc.Protocol;

public class SpiTest {

    public static void main(String[] args) {

        ExtensionLoader<Protocol> extensionLoader = ExtensionLoader.getExtensionLoader(Protocol.class);
        extensionLoader.getActivateExtension();
        Protocol http = extensionLoader.getExtension("http");
        System.out.println(http);
    }
}
