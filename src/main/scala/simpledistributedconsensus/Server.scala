package simpledistributedconsensus

import java.util.UUID
import java.util.concurrent.TimeoutException

import scala.concurrent.{Future, Await}
import scala.concurrent.duration._
import scala.collection.immutable.HashMap
import scala.util.{Success, Failure, Try}
import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.actor.typed.{ActorSystem => TypedActorSystem, ActorRef, Behavior}
import akka.actor.typed.scaladsl.{Behaviors, StashBuffer}
import akka.actor.typed.scaladsl.AskPattern._
import akka.event.Logging
import akka.stream.{Materializer, OverflowStrategy}
import akka.stream.scaladsl.{Tcp, Flow, Source, Sink, Keep}
import akka.stream.scaladsl.Tcp.{IncomingConnection, ServerBinding}
import akka.stream.typed.scaladsl.{ActorSource, ActorFlow}
import akka.util.{ByteString, Timeout}

import Protocol.ClientUserMsg.{AbstractClientUserMsg, PaybackRequest, CreditRequest, QuitUser}
import Protocol.ClientNodeMsg.{AbstractClientNodeMsg, Join, Poll, Vote, VoteIn, VoteOut}
import Protocol.ServerClientUserMsg.{AbstractServerClientUserMsg, Welcome, Accepted, Rejected, RequestTimedout}
import Protocol.ServerClientNodeMsg.{BaseServerClientNodeMsg, PaybackRequestForward, CreditRequestForward, PaybackSplit, CreditSplit}

// AddNodePoll should handle the evaluation in async so that timeouts, if already received in the mailbox can be processed


object Server:

  implicit val system: ActorSystem = ActorSystem("tcp-server")
  import system.dispatcher
  import LeaderActor._

  given Timeout = Timeout(3.seconds)

  def clientNodeFlow(ref: ActorRef[BaseServerClientNodeMsg],
      source: Source[BaseServerClientNodeMsg, NotUsed]):
      Flow[ByteString, ByteString, NotUsed] = 
    Protocol.ClientNodeMsg.decoder
    .collect{
      case Success(msg) => 
        msg match
          case Join(name) =>  
            system.log.info(s"Node ${name} has joined the cluster")
            leaderActor ! AddNode(name, ref)
          case Poll(vote, amount, id, name) =>
            leaderActor ! AddNodePoll(vote, amount, id, name)
    }
    .via(Flow[Unit])
    .map((_: Unit) => ByteString.empty)
    .merge(source.via(Protocol.ServerClientNodeMsg.encoder))


  def clientUserFlow(sessionId: UUID, ref: ActorRef[AbstractServerClientUserMsg[_]],
      source: Source[AbstractServerClientUserMsg[_], NotUsed]): 
      Flow[ByteString, ByteString, NotUsed] = 
    Protocol.ClientUserMsg.decoder
    .takeWhile(_ != Success(QuitUser(true)))
    .map(msg => leaderActor ! StartSession(sessionId, msg.get, ref))
    .via(Flow[Unit])
    .map((_: Unit) => ByteString.empty)
    .merge{
      Source.single{
        val welcomeMsg = 
          s"""
          | Greetings from the State Lending Management. 
          | Kindly choose one of the following options
          | 1. Payback Request
          | 2. Credit Request """.stripMargin('|')
        Welcome(welcomeMsg)
      }  
      .concat(source).via(Protocol.ServerClientUserMsg.encoder)
    }


  def composeClientUser(host: String, port: Int): Future[ServerBinding] = 
    Tcp(system).bind(host, port)
    .to(Sink.foreach{(incomingConnection: IncomingConnection) =>  
      val sessionId = UUID.randomUUID()
      val (userFlowActorRef, userFlowSource) = ActorSource
      .actorRef[AbstractServerClientUserMsg[_]]({
        PartialFunction.empty
      },{
        PartialFunction.empty[AbstractServerClientUserMsg[_], Exception]
      }, 20, OverflowStrategy.dropHead)
      .preMaterialize()       
      incomingConnection.handleWith(clientUserFlow(sessionId, userFlowActorRef, userFlowSource))
    })
    .run()
    

  def composeClientNode(host: String, port: Int): Future[ServerBinding] = 
    
    Tcp(system).bind(host, port)
    .to(Sink.foreach{(incomingConnection: IncomingConnection) => 
      val (nodeFlowActorRef, nodeFlowSource) = ActorSource
      .actorRef[BaseServerClientNodeMsg]({
        PartialFunction.empty
      },{
        PartialFunction.empty[BaseServerClientNodeMsg, Exception]
      },20, OverflowStrategy.dropHead)
      .preMaterialize() 
      incomingConnection.handleWith(clientNodeFlow(nodeFlowActorRef, nodeFlowSource))
    })
    .run()    


  def main(args: Array[String]): Unit =
    try{
      composeClientUser(args(0), args(1).toInt).onComplete{_ =>
        system.log.info("Server bound on user port")
      }  
      composeClientNode(args(0), args(2).toInt).onComplete{_ =>
        system.log.info("Server bound on node port")
      }     
    }
    catch{
      case e: NumberFormatException => system.log.error("Program failed with ", e.getMessage)
        System.exit(1)
    }
