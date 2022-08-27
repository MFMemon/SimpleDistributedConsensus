package simpledistributedconsensus

import scala.concurrent.{Future, Await}
import scala.concurrent.duration._
import scala.io.StdIn
import scala.util.{Success, Failure}
import akka.actor.ActorSystem
import akka.event.Logging
import akka.stream.scaladsl.{Flow, RunnableGraph, Source, Sink, Tcp}
import akka.stream.scaladsl.Tcp.OutgoingConnection
import akka.util.ByteString
import akka.NotUsed

import Protocol.ClientUserMsg.{AbstractClientUserMsg, PaybackRequest, CreditRequest, QuitUser}
import Protocol.ServerClientUserMsg.{AbstractServerClientUserMsg, Welcome, Accepted, Rejected}

object Client:

  type TcpFlow = Flow[ByteString, ByteString, Future[OutgoingConnection]]
  
  implicit val system: ActorSystem = ActorSystem("tcp-client")
  import system.dispatcher


  def connHandler(host: String, port: Int): TcpFlow = 
    Tcp(system).outgoingConnection(host, port)
  

  def parseInput(choice: String, amount: Option[Int], name: String): AbstractClientUserMsg[_] = 
    choice match
      case "1" => PaybackRequest(amount.get, name)
      case "2" => CreditRequest(amount.get, name)


  def msgHandler(name: String): Flow[ByteString, ByteString, NotUsed] = 
    Protocol.ServerClientUserMsg.decoder
    .collect{
      case Success(sm) => 
        sm match
          case Welcome(msg) => 
            system.log.info(msg)
            val choice = StdIn.readLine()
            system.log.info("""
              | Please enter the amount in USD""".stripMargin('|'))
            val amount = StdIn.readLine()
            parseInput(choice, amount.toIntOption, name)
          case _ => 
            system.log.info(sm.asInstanceOf[AbstractServerClientUserMsg[String]].msg)
            QuitUser(true)
      }
    .via(Protocol.ClientUserMsg.encoder)
    

  def compose(host: String, port: Int, name: String): RunnableGraph[NotUsed] = 
    connHandler(host, port)
    .via(msgHandler(name))
    .watchTermination(){(_, terminationFuture) => 
      terminationFuture.onComplete{
        case Success(x) => 
          println("Exiting on stream completion")
          System.exit(0)
        case Failure(e) => 
          println(s"Exiting on error: ${e.getMessage}")
          System.exit(1)
      }
      NotUsed
    }
    .join(Flow[ByteString])


  def main(args: Array[String]) : Unit = 
    try{
      val host = args(0)
      val port = args(1).toInt
      val name = args(2)
      compose(host, port, name).run()
    }
    catch{
      case e: java.lang.NumberFormatException => system.log.error("Program failed with ", e.getMessage)
      System.exit(1)
    }
 
  






