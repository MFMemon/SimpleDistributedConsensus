package simpledistributedconsensus

import scala.concurrent.{Future, Await}
import scala.concurrent.duration._
import scala.collection.immutable.HashMap
import scala.util.{Success, Failure}
import akka.actor.ActorSystem
import akka.event.Logging
import akka.stream.scaladsl.{Flow, RunnableGraph, Source, Sink, Tcp}
import akka.stream.scaladsl.Tcp.OutgoingConnection
import akka.util.ByteString
import akka.NotUsed

import Protocol.ClientNodeMsg.{Join, Poll, VoteIn, VoteOut}
import Protocol.ServerClientNodeMsg.{BaseServerClientNodeMsg, PaybackRequestForward, CreditRequestForward, PaybackSplit, CreditSplit}

object Node:

  type TcpFlow = Flow[ByteString, ByteString, Future[OutgoingConnection]]

  private var balance = 0
  private var accounts = HashMap.empty[String, Int]
  
  implicit val system: ActorSystem = ActorSystem("tcp-node")
  import system.dispatcher


  def connHandler(host: String, port: Int): TcpFlow = 
    Tcp(system).outgoingConnection(host, port)


  def msgHandler(name: String): Flow[ByteString, ByteString, NotUsed] = 
    Protocol.ServerClientNodeMsg.decoder
    .collect{
      case Success(msg) =>
        msg match
          case PaybackRequestForward(amount, id, username) =>
            val user = accounts.get(username)
            val moneyOwed = if user.isDefined then user.get else 0
            if moneyOwed > 0 then
              Poll(VoteIn, Some(moneyOwed), id, name)
            else
              Poll(VoteOut, None, id, name)
          case CreditRequestForward(amount, id, username) =>
            if balance >= 1.5 * amount then
              Poll(VoteIn, None, id, name)
            else
              Poll(VoteOut, None, id, name)
          case PaybackSplit(amount, username) =>
            balance += amount
            accounts += username -> (accounts.get(username).get - amount)
          case CreditSplit(amount, username) =>
            balance -= amount
            val user = accounts.get(username)
            val currentOwedMoney = if user.isDefined then user.get else 0
            accounts += username -> (currentOwedMoney + amount)
      }
    .collect{
      case x: Poll => x
    }
    // .filterNot{res => res match
    //    case _: Unit => true }      
    .merge(Source.single(Join(name)))
    .via(Protocol.ClientNodeMsg.encoder)
    

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
      balance = args(3).toInt
      compose(host, port, name).run()
    }
    catch{
      case e: java.lang.NumberFormatException => system.log.error("Program failed with ", e.getMessage)
      System.exit(1)
    }
 
  






