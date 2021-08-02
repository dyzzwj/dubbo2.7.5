dubbo框架设计
1、dubbo-registry——注册中心模块
dubbo的注册中心实现有Multicast注册中心、Zookeeper注册中心、Redis注册中心、Simple注册中心等，这个模块就是封装了dubbo所支持的注册中心的实现。
    dubbo-registry-api：抽象了注册中心的注册和发现，实现了一些公用的方法，让子类只关注部分关键方法。
    剩下几个包分别是几种注册中心实现方法的封装，其中dubbo-registry-default就是官方文档里面的Simple注册中心。


2、dubbo-cluster——集群模块
官方文档的解释：将多个服务提供方伪装为一个提供方，包括：负载均衡, 容错，路由等，集群的地址列表可以是静态配置的，也可以是由注册中心下发
我的理解：它就是一个解决出错情况采用的策略，这个模块里面封装了多种策略的实现方法，并且也支持自己扩展集群容错策略，cluster把多个Invoker伪装成一个Invoker，并且在伪装过程中加入了容错逻辑，失败了，重试下一个
    configurator包：配置包，dubbo的基本设计原则是采用URL作为配置信息的统一格式，所有拓展点都通过传递URL携带配置信息，这个包就是用来根据统一的配置规则生成配置信息。
    directory包：Directory 代表了多个 Invoker，并且它的值会随着注册中心的服务变更推送而变化 。这里介绍一下Invoker，Invoker是Provider的一个调用Service的抽象，Invoker封装了Provider地址以及Service接口信息。
    loadbalance包：封装了负载均衡的实现，负责利用负载均衡算法从多个Invoker中选出具体的一个Invoker用于此次的调用，如果调用失败，则需要重新选择。
    merger包：封装了合并返回结果，分组聚合到方法，支持多种数据结构类型。
    router包：封装了路由规则的实现，路由规则决定了一次dubbo服务调用的目标服务器，路由规则分两种：条件路由规则和脚本路由规则，并且支持可拓展。
    support包：封装了各类Invoker和cluster，包括集群容错模式和分组聚合的cluster以及相关的Invoker



3、dubbo-common——公共逻辑模块
官方文档的解释：包括 Util 类和通用模型。

我的理解：这个应该很通俗易懂，工具类就是一些公用的方法，通用模型就是贯穿整个项目的统一格式的模型，比如URL，上述就提到了URL贯穿了整个项目

4、dubbo-config——配置模块
官方文档的解释：是 Dubbo 对外的 API，用户通过 Config 使用Dubbo，隐藏 Dubbo 所有细节。

我的理解：用户都是使用配置来使用dubbo，dubbo也提供了四种配置方式，包括XML配置、属性配置、API配置、注解配置，配置模块就是实现了这四种配置的功能。
    dubbo-config-api：实现了API配置和属性配置的功能。
    dubbo-config-spring：实现了XML配置和注解配置的功能。

5、dubbo-rpc——远程调用模块
官方文档的解释：抽象各种协议，以及动态代理，只包含一对一的调用，不关心集群的管理。

我的理解：远程调用，最主要的肯定是协议，dubbo提供了许许多多的协议实现，不过官方推荐时使用dubbo自己的协议，还给出了一份性能测试报告。
    dubbo-rpc-api：抽象了动态代理和各类协议，实现一对一的调用
    另外的包都是各个协议的实现
    
6、dubbo-remoting——远程通信模块
官方文档的解释：相当于 Dubbo 协议的实现，如果 RPC 用 RMI协议则不需要使用此包。

我的理解：提供了多种客户端和服务端通信功能，比如基于Grizzly、Netty、Tomcat等等，RPC除了RMI的协议都要用到此模块。
    dubbo-remoting-api：定义了客户端和服务端的接口。
    dubbo-remoting-grizzly：基于Grizzly实现的Client和Server。
    dubbo-remoting-http：基于Jetty或Tomcat实现的Client和Server。
    dubbo-remoting-mina：基于Mina实现的Client和Server。
    dubbo-remoting-netty：基于Netty3实现的Client和Server。
    Dubbo-remoting-netty4：基于Netty4实现的Client和Server。
    dubbo-remoting-p2p：P2P服务器，注册中心multicast中会用到这个服务器使用。
    dubbo-remoting-zookeeper：封装了Zookeeper Client ，和 Zookeeper Server 通信。

7、dubbo-container——容器模块
官方文档的解释：是一个 Standlone 的容器，以简单的 Main 加载 Spring 启动，因为服务通常不需要 Tomcat/JBoss 等 Web 容器的特性，没必要用 Web 容器去加载服务。

我的理解：因为后台服务不需要Tomcat/JBoss 等 Web 容器的功能，不需要用这些厚实的容器去加载服务提供方，既资源浪费，又增加复杂度。服务容器只是一个简单的Main方法，加载一些内置的容器，也支持扩展容器。
    dubbo-container-api：定义了Container接口，实现了服务加载的Main方法。
    其他三个分别提供了对应的容器，供Main方法加载。

8、dubbo-monitor——监控模块
官方文档的解释：统计服务调用次数，调用时间的，调用链跟踪的服务。

我的理解：这个模块很清楚，就是对服务的监控。
    dubbo-monitor-api：定义了monitor相关的接口，实现了监控所需要的过滤器。
    dubbo-monitor-default：实现了dubbo监控相关的功能。

9、dubbo-demo——示例模块
  这个模块是快速启动示例，其中包含了服务提供方和调用方，注册中心用的是multicast，用XML配置方法，具体的介绍可以看官方文档。

10、dubbo-filter——过滤器模块
这个模块提供了内置的一些过滤器。
    dubbo-filter-cache：提供缓存过滤器。
    dubbo-filter-validation：提供参数验证过滤器。

11、dubbo-plugin——插件模块
该模块提供了内置的插件
    dubbo-qos：提供了在线运维的命令。
    
12、dubbo-serialization——序列化模块
该模块中封装了各类序列化框架的支持实现。
  dubbo-serialization-api：定义了Serialization的接口以及数据输入输出的接口。
  其他的包都是实现了对应的序列化框架的方法。dubbo内置的就是这几类的序列化框架，序列化也支持扩展。  
