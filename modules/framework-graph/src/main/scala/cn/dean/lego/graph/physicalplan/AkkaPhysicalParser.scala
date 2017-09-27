
package cn.dean.lego.graph.physicalplan

import akka.NotUsed
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.stream._
import akka.stream.javadsl.RunnableGraph
import akka.stream.scaladsl.{Balance, Broadcast, Concat, Flow, GraphDSL, Merge, MergePreferred, Sink, Source, ZipWith}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import cn.dean.lego.common.exceptions.FlowRunFailedException
import cn.dean.lego.common.loader.ComponentLoader
import cn.dean.lego.common.log.Logger
import cn.dean.lego.common.models.NodeType
import cn.dean.lego.common.rules.ComponentResult
import cn.dean.lego.graph.models.{GraphNode, NodeProp}
import com.typesafe.config.Config
import org.apache.spark.SparkContext
import scaldi.Injectable.inject
import scaldi.Injector
import scaldi.akka.AkkaInjectable

/**
  * Created by deanzhang on 2017/8/22.
  */

class AkkaPhysicalParser(implicit injector: Injector) extends GraphPhysicalParser[NodeProp, RunnableGraph[NotUsed]] {

  type Result = Seq[ComponentResult]

  private implicit val actorSystem: ActorSystem = inject[ActorSystem]
  private val logger = inject[Logger]

  private val notifyActor = AkkaInjectable.injectActorRef[NotifyActor]("notifyActor")
  logger.info("Start up notifyActor")

  private val decider: Supervision.Decider = {
    case _: IllegalArgumentException =>
      Supervision.Restart
    case e =>
      val lstTrace = e.getStackTrace.map(_.toString).mkString("\n")
      val err = s"${e.toString}\n$lstTrace"
      logger.error(err)
      val result = ComponentResult(succeed = false, s"Got exception: $err", None)
      val stop = Supervision.Stop
      notifyActor ! result
      stop
  }

  // implicit actor materializer
  private implicit val materializer = ActorMaterializer(ActorMaterializerSettings(actorSystem).withSupervisionStrategy(decider))

  import GraphDSL.Implicits._

  override def parse(sc: SparkContext, nodes: Seq[GraphNode[NodeProp]]): RunnableGraph[NotUsed] = {

    val source = Source.single(Seq(ComponentResult(succeed = true, "start", None)))
    val sink = Sink.actorRef(notifyActor, onCompleteMessage = "notify finished")
    RunnableGraph.fromGraph(GraphDSL.create() {
      implicit builder =>

        val flowMap = nodes.map {
          n =>
            val inOuts = getFlow(builder, sc, n)
            (n.id, inOuts)
        }.toMap

        val (startIn, _) = flowMap(nodes.filter(_.inputs.isEmpty).head.id)
        source ~> startIn.head.inlets

        val nonEmptyOutputNodes = nodes.filter(_.outputs.nonEmpty)
        nonEmptyOutputNodes.foreach {
          n =>
            val (_, flowOut) = flowMap(n.id)
            val inlets = n.outputs.map(nn => flowMap(nn.id))
            flowOut.indices.foreach {
              i =>
                val (in, _) = inlets(i)
                val connIn = in.filter(!_.isConnected).head
                flowOut(i).outlets ~> connIn.inlets
                connIn.connect()
                flowOut(i).connect()
            }
        }

        val endNodes = nodes.filter(_.outputs.isEmpty)

        if (endNodes.length > 1) {
          val merge = builder.add(Merge[Result](endNodes.length))
          merge.out ~> sink
          val inlets = merge.inSeq.map(new NodeIn(_))
          endNodes.foreach {
            n =>
              val (_, flowOut) = flowMap(n.id)
              val connIn = inlets.filter(!_.isConnected).head
              flowOut.head.outlets ~> connIn.inlets
              connIn.connect()
              flowOut.head.connect()

          }
        } else {
          val (_, flowOut) = flowMap(endNodes.head.id)
          flowOut.head.outlets ~> sink
          flowOut.head.connect()
        }
        ClosedShape
    })

  }

  def run(sc: SparkContext, nodes: Seq[GraphNode[NodeProp]]): NotUsed = {
    val g = parse(sc, nodes)
    println(s"g:\n$g")
    g.run(materializer)
  }

  private def getFlow(implicit builder: GraphDSL.Builder[NotUsed], sc: SparkContext, node: GraphNode[NodeProp]) = {
    val name = node.info.structConf.getString("name")
    val flow = builder.add(Flow[Result].map {
      lastResult =>
        /*val jarName = structConf.getString("jar-name")
        val className = structConf.getString("class-name")
        val assembly = ComponentLoader.load(jarName, className)
        val lastRdd = lastResult.head.result
        //执行assembly，获得结果
        // todo 这里的lastRdd可以是个Map结构，适应merge的情况
        val Some(currResult) = assembly.run(sc, paramConf, lastRdd)
        if (!currResult.succeed) {
          throw FlowRunFailedException(s"$nodeType [$index-$name] run failed, message: \n${currResult.message}")
        } else Seq(currResult)*/
        //lastResult.zipWithIndex.foreach{case (r, i) => println(s"$i: $r")}
        //println(s"$name: ${lastResult.map(_.message).zipWithIndex}")
        println(s"$name#${System.nanoTime()}#${lastResult.map(_.message).mkString(",")}")
        Seq(ComponentResult(succeed = true, s"[$name]", None))

    })

    val inlets = (if (node.inputs.nonEmpty) {
      if (node.inputs.length > 1) {
        /*val merge = builder.add(Merge[Result](node.inputs.length))
        merge.out ~> flow
        merge.inSeq*/
        val zipWith = builder.add(getZipWithNode(node.inputs.length))
        zipWith.out ~> flow
        zipWith.inlets.map(_.as[Result])
      } else {
        Seq(flow.in)
      }
    } else {
      Seq(flow.in)
    }).map(new NodeIn(_))

    val outlets = (if (node.outputs.nonEmpty) {
      if (node.outputs.length > 1) {
        val broadcast = builder.add(Broadcast[Result](node.outputs.length))
        flow ~> broadcast.in
        //broadcast.outArray.foreach(_.async)
        broadcast.outArray.toSeq
      } else {
        Seq(flow.out)
      }
    } else {
      Seq(flow.out)
    }).map(new NodeOut(_))
    (inlets, outlets)
  }

  private def getZipWithNode(length: Int) ={
    length match {
      case 2 => ZipWith[Result, Result, Result]((r1, r2) => r1 ++ r2)
      case 3 => ZipWith[Result, Result, Result, Result]((r1, r2, r3) => r1 ++ r2 ++ r3)
      case 4 => ZipWith[Result, Result, Result, Result, Result]((r1, r2, r3, r4) => r1 ++ r2 ++ r3 ++ r4)
      case 5 => ZipWith[Result, Result, Result, Result, Result, Result]((r1, r2, r3, r4, r5) => r1 ++ r2 ++ r3 ++ r4 ++ r5)
      case 6 => ZipWith[Result, Result, Result, Result, Result, Result, Result]((r1, r2, r3, r4, r5, r6) => r1 ++ r2 ++ r3 ++ r4 ++ r5 ++ r6)
      case 7 => ZipWith[Result, Result, Result, Result, Result, Result, Result, Result]((r1, r2, r3, r4, r5, r6, r7) => r1 ++ r2 ++ r3 ++ r4 ++ r5 ++ r6 ++ r7)
      case 8 => ZipWith[Result, Result, Result, Result, Result, Result, Result, Result, Result]((r1, r2, r3, r4, r5, r6, r7, r8) => r1 ++ r2 ++ r3 ++ r4 ++ r5 ++ r6 ++ r7 ++ r8)
      case 9 => ZipWith[Result, Result, Result, Result, Result, Result, Result, Result, Result, Result]((r1, r2, r3, r4, r5, r6, r7, r8, r9) => r1 ++ r2 ++ r3 ++ r4 ++ r5 ++ r6 ++ r7 ++ r8 ++ r9)
      case 10 => ZipWith[Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result]((r1, r2, r3, r4, r5, r6, r7, r8, r9, r10) => r1 ++ r2 ++ r3 ++ r4 ++ r5 ++ r6 ++ r7 ++ r8 ++ r9 ++ r10)
      case 11 => ZipWith[Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result]((r1, r2, r3, r4, r5, r6, r7, r8, r9, r10, r11) => r1 ++ r2 ++ r3 ++ r4 ++ r5 ++ r6 ++ r7 ++ r8 ++ r9 ++ r10 ++ r11)
      case 12 => ZipWith[Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result]((r1, r2, r3, r4, r5, r6, r7, r8, r9, r10, r11, r12) => r1 ++ r2 ++ r3 ++ r4 ++ r5 ++ r6 ++ r7 ++ r8 ++ r9 ++ r10 ++ r11 ++ r12)
      case 13 => ZipWith[Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result]((r1, r2, r3, r4, r5, r6, r7, r8, r9, r10, r11, r12, r13) => r1 ++ r2 ++ r3 ++ r4 ++ r5 ++ r6 ++ r7 ++ r8 ++ r9 ++ r10 ++ r11 ++ r12 ++ r13)
      case 14 => ZipWith[Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result]((r1, r2, r3, r4, r5, r6, r7, r8, r9, r10, r11, r12, r13, r14) => r1 ++ r2 ++ r3 ++ r4 ++ r5 ++ r6 ++ r7 ++ r8 ++ r9 ++ r10 ++ r11 ++ r12 ++ r13 ++ r14)
      case 15 => ZipWith[Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result]((r1, r2, r3, r4, r5, r6, r7, r8, r9, r10, r11, r12, r13, r14, r15) => r1 ++ r2 ++ r3 ++ r4 ++ r5 ++ r6 ++ r7 ++ r8 ++ r9 ++ r10 ++ r11 ++ r12 ++ r13 ++ r14 ++ r15)
      case 16 => ZipWith[Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result]((r1, r2, r3, r4, r5, r6, r7, r8, r9, r10, r11, r12, r13, r14, r15, r16) => r1 ++ r2 ++ r3 ++ r4 ++ r5 ++ r6 ++ r7 ++ r8 ++ r9 ++ r10 ++ r11 ++ r12 ++ r13 ++ r14 ++ r15 ++ r16)
      case 17 => ZipWith[Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result]((r1, r2, r3, r4, r5, r6, r7, r8, r9, r10, r11, r12, r13, r14, r15, r16, r17) => r1 ++ r2 ++ r3 ++ r4 ++ r5 ++ r6 ++ r7 ++ r8 ++ r9 ++ r10 ++ r11 ++ r12 ++ r13 ++ r14 ++ r15 ++ r16 ++ r17)
      case 18 => ZipWith[Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result]((r1, r2, r3, r4, r5, r6, r7, r8, r9, r10, r11, r12, r13, r14, r15, r16, r17, r18) => r1 ++ r2 ++ r3 ++ r4 ++ r5 ++ r6 ++ r7 ++ r8 ++ r9 ++ r10 ++ r11 ++ r12 ++ r13 ++ r14 ++ r15 ++ r16 ++ r17 ++ r18)
      case 19 => ZipWith[Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result]((r1, r2, r3, r4, r5, r6, r7, r8, r9, r10, r11, r12, r13, r14, r15, r16, r17, r18, r19) => r1 ++ r2 ++ r3 ++ r4 ++ r5 ++ r6 ++ r7 ++ r8 ++ r9 ++ r10 ++ r11 ++ r12 ++ r13 ++ r14 ++ r15 ++ r16 ++ r17 ++ r18 ++ r19)
      case 20 => ZipWith[Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result, Result]((r1, r2, r3, r4, r5, r6, r7, r8, r9, r10, r11, r12, r13, r14, r15, r16, r17, r18, r19, r20) => r1 ++ r2 ++ r3 ++ r4 ++ r5 ++ r6 ++ r7 ++ r8 ++ r9 ++ r10 ++ r11 ++ r12 ++ r13 ++ r14 ++ r15 ++ r16 ++ r17 ++ r18 ++ r19 ++ r20)

      case _ => throw new UnsupportedOperationException(s"Not supported ZipWith length [$length]")
    }
  }

  def test() = {

    def getFlow(name: String)(implicit builder: GraphDSL.Builder[NotUsed]) = {
      Flow[String].map {
        s =>
          val now = System.nanoTime()
          val msg = s"$name#${now.toString}: $s"
          println(msg)
          msg
      }
    }

    val source = Source.single("start")
    val sink = Sink.ignore
    val g = RunnableGraph.fromGraph(GraphDSL.create() {
      implicit builder =>

        val f1 = getFlow("f1")
        val f2 = getFlow("f2")
        val f3 = getFlow("f3")
        val f4 = getFlow("f4")
        //val merge = builder.add(Merge[String](2))
        val merge = builder.add(ZipWith[String, String, String]({(a, b) => a + b}))
        val broadcast = builder.add(Broadcast[String](2))
        //val broadcast = builder.add(Balance[String](2, true))

        source ~> f1 ~> broadcast ~> f2 ~> merge.in0
        broadcast ~> f3 ~> merge.in1
        merge.out ~> f4 ~> sink

        ClosedShape
    })
    g.run(materializer)
  }
}


/*
     val f1 = Flow[Seq[ComponentResult]].map{
        lastResult =>
          val name = "name"
          val index = "index"
          val nodeType = NodeType.assembly
          nodeType match {
            case NodeType.assembly =>
              val jarName = "jar-name"
              val className = "class-name"
              val assembly = ComponentLoader.load(jarName, className)
              val lastRdd = lastResult.head.result
              //执行assembly，获得结果
              val Some(currResult) = assembly.run(sc, null, lastRdd)
              if(!currResult.succeed){
                throw FlowRunFailedException(s"$nodeType [$index-$name] run failed, message: \n${currResult.message}")
              } else Seq(currResult)
            case _ =>
              Seq(ComponentResult(succeed = true, s"$nodeType [$index-$name]", None))
          }
      }

      val f2 = Flow[Seq[ComponentResult]].map{
        lastResult =>
          val name = "name"
          val index = "index"
          val nodeType = NodeType.assembly
          nodeType match {
            case NodeType.assembly =>
              val jarName = "jar-name"
              val className = "class-name"
              val assembly = ComponentLoader.load(jarName, className)
              val lastRdd = lastResult.head.result
              //执行assembly，获得结果
              val Some(currResult) = assembly.run(sc, null, lastRdd)
              if(!currResult.succeed){
                throw FlowRunFailedException(s"$nodeType [$index-$name] run failed, message: \n${currResult.message}")
              } else Seq(currResult)
            case _ =>
              Seq(ComponentResult(succeed = true, s"$nodeType [$index-$name]", None))
          }
      }

      val source = builder.add(Source.single(Seq(ComponentResult(true, "", None))))

      val flow1 = builder.add(f1)
      val flow2 = builder.add(f2)

      val broadcast = builder.add(Broadcast[Seq[ComponentResult]](2))

      val merge = builder.add(Merge[Seq[ComponentResult]](2))

      flow1 ~> broadcast ~> flow1 ~> merge ~> flow2
                         broadcast ~> flow2 ~> merge*/

/*private def generateShape(structConf: Config, paramConf: Option[Config]): GraphStage[FlowShape[ComponentResult, ComponentResult]] = {
  new GraphStage[FlowShape[ComponentResult, ComponentResult]] {
    val in = Inlet[ComponentResult]("flowStage.in")
    val out = Outlet[ComponentResult]("flowStage.out")

    override val shape = FlowShape(in, out)

    override def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) {
      setHandler(in, new InHandler {
        override def onPush() = grab(in) match {
          case lastResult ⇒
            val isAssembly = !structConf.getConfig("jar-name").isEmpty
            val newResult = if (isAssembly) {

            } else {

            }
            push(out, newResult)

        }
      })
      setHandler(out, new OutHandler {
        override def onPull() = pull(in)
      })
    }
  }
}*/




