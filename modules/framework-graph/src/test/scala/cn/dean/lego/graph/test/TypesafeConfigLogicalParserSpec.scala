package cn.dean.lego.graph.test

import cn.dean.lego.common.config.ConfigLoader
import cn.dean.lego.graph.logicplan.TypesafeConfigLogicalParser
import org.scalatest.FlatSpec

/**
  * Created by deanzhang on 2017/8/20.
  */
class TypesafeConfigLogicalParserSpec extends FlatSpec {

  /*it should "parse module succeed" in {
    val path = "/Users/deanzhang/work/code/github/lego-framework/sample/systemA/appA/moduleA/conf/application.conf"
    val conf = ConfigLoader.load(path, None)
    val nodes = TypesafeConfigLogicalParser.parse(conf)
    nodes.foreach(println)
    assert(nodes.length === 2)
  }*/

  /*it should "parse application succeed" in {
    val path = "/Users/deanzhang/work/code/github/lego-framework/sample/systemA/appA/conf/application.conf"
    val conf = ConfigLoader.load(path, None)
    val nodes = TypesafeConfigLogicalParser.parse(conf)
    nodes.foreach(println)
    assert(nodes.length === 2)
  }*/

  it should "parse system succeed" in {
    val path = "/Users/deanzhang/work/code/github/lego-framework/sample/systemA/conf/application.conf"
    val conf = ConfigLoader.load(path, None)
    val nodes = TypesafeConfigLogicalParser.parse(conf)
    nodes.foreach(println)
    assert(nodes.length === 2)
  }
}