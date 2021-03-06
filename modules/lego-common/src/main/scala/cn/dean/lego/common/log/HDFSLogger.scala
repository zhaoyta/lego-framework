package cn.dean.lego.common.log

import cn.dean.lego.common.utils.HDFSUtil
import org.joda.time.DateTime

/**
  * Created by deanzhang on 2017/6/26.
  */
/**
  * HDFS上的写日志接口
  * @param logDir 日志存放路径，必须以"hdfs://"开头
  */
class HDFSLogger(logDir: String) extends LoggerAPI{

  val logPath = s"$logDir/$logFileName"
  private def log(typ: String, content: String): Unit = {
    val line = s"${DateTime.now().toString("yyyy-MM-dd HH:mm:ss")}_ ${typ}_ $content"
    println(line)
    HDFSUtil.appendFile(logPath, line + "\n")
  }

  override def info(content: String): Unit = log("INFO", content)

  override def warn(content: String): Unit = log("WARN", content)

  override def error(content: String): Unit = log("ERROR", content)
}
