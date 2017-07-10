package cn.jw.rms.data.framework.common.rules

import org.apache.spark.rdd.RDD

/**
  * Created by deanzhang on 2016/12/26.
  */
/**
  * assembly执行结果结构体
  * @param succeed 是否执行成功
  * @param message 返回的消息，可以是记录性质信息也可以是错误信息
  * @param result assembly执行返回的RDD
  */
case class AssemblyResult(succeed: Boolean, message: String, result: Option[RDD[String]])