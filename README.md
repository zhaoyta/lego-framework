## lego-framework
乐高框架是一个基于Spark的模块化计算框架，每个模块都是一个实现了框架接口的独立的jar包，通过配置文件将模块链接成一个流程图，框架会执行流程图，得到最终结果。<br/>
框架名称之所以叫乐高，是因为乐高是目前市面上很牛逼的积木品牌，积木可以充分体现模块化的设计思路，所以使用lego命名新一代框架，彰显框架的高度模块化与灵活扩展。

## V3.0.0

使用akka和scaldi重构框架，支持并行的流程。

配置并行流程的方法：<br/>
设置conf/application.conf中的index字段，其值存在"."，则表示有一层并行流程。<br/>
比如，<br/>
assembly [a]的index = 1<br/>
assembly [b]的index = 1.1<br/>
assembly [c]的index = 1.2<br/>
assembly [d]的index = 2<br/>
<br/>
则b和c为一个并行流程，即整体执行流程图如下：

       |-> b -|
       |      |
    a -        -> d
       |      |
       |-> c -|


具体可参加sample/s1中的实例（可本地执行）。


