package simpledistributedconsensus

import java.util.UUID

import scala.concurrent.duration._
import scala.collection.immutable.HashMap
import akka.actor.typed.{ActorSystem => TypedActorSystem, ActorRef, Behavior}
import akka.actor.typed.scaladsl.{Behaviors, StashBuffer}

import Protocol.ClientUserMsg.{AbstractClientUserMsg, PaybackRequest, CreditRequest, QuitUser}
import Protocol.ClientNodeMsg.{AbstractClientNodeMsg, Join, Poll, Vote, VoteIn, VoteOut}
import Protocol.ServerClientUserMsg.{AbstractServerClientUserMsg, Welcome, Accepted, Rejected, RequestTimedout}
import Protocol.ServerClientNodeMsg.{BaseServerClientNodeMsg, PaybackRequestForward, CreditRequestForward, PaybackSplit, CreditSplit}
  
object LeaderActor:

  sealed trait ActorCommand
  
  case class AddNode(name: String, ref: ActorRef[BaseServerClientNodeMsg]) extends ActorCommand

  case class StartSession(
    sessionId: UUID, request: AbstractClientUserMsg[_],
    replyTo: ActorRef[AbstractServerClientUserMsg[_]]) extends ActorCommand

  case class AddNodePoll(vote: Vote, amount: Option[Int], 
    sessionId: UUID, nodename: String) extends ActorCommand

  case class SessionTimeout(sessionId: UUID) extends ActorCommand


  def evaluateConsensus(
      polls: List[AddNodePoll], 
      request: AbstractClientUserMsg[_]): HashMap[String, BaseServerClientNodeMsg] = 

    val requestMatcher: PartialFunction[AbstractClientUserMsg[_], HashMap[String, BaseServerClientNodeMsg]] = 
      case req: PaybackRequest =>
        val totalOwed = polls.foldLeft(0){(agg, elem) => 
          val amount = elem.amount
          if amount.isDefined then amount.get + agg else agg
        }    
        if req.msg > totalOwed then 
          HashMap.empty[String, BaseServerClientNodeMsg]
        else
          val splitFactor = req.msg / totalOwed
          HashMap.from{
            polls.collect{comm => comm.amount match
              case Some(amount) => (comm.nodename, 
                PaybackSplit((amount * splitFactor).toInt, req.username))
            }
          }
      case req: CreditRequest => 
        val totalVotes = polls.count{comm => comm.vote match
          case VoteIn => true
          case _ => false   
        }
        if totalVotes < polls.size then
          HashMap.empty[String, BaseServerClientNodeMsg]
        else
          var splits = (1 to req.msg)
            .groupBy(_ % totalVotes)
            .toList.map((k,v) => v.size)
          HashMap.from{
            polls.collect{comm => comm.vote match
              case VoteIn => 
                val splitAmount = splits.head
                splits = splits.tail
                (comm.nodename, CreditSplit(splitAmount, req.username))
            }
          }
    requestMatcher(request)


  val leaderActor = TypedActorSystem(
    coreLogicHandler(HashMap.empty[String, ActorRef[BaseServerClientNodeMsg]]), 
    "root-actor")

  def coreLogicHandler(nodes: HashMap[String, ActorRef[BaseServerClientNodeMsg]]):
      Behavior[ActorCommand] = 
    Behaviors.withStash(20)(idleSession(_, nodes, evaluateConsensus))

  
  def idleSession(buffer: StashBuffer[ActorCommand], 
      nodes: HashMap[String, ActorRef[BaseServerClientNodeMsg]],
      f: (List[AddNodePoll], AbstractClientUserMsg[_]) => HashMap[String, BaseServerClientNodeMsg]):
      Behavior[ActorCommand] = 
    Behaviors.receiveMessagePartial{
      case AddNode(name, ref) => idleSession(buffer, nodes + (name -> ref), f)
      case StartSession(id, req, ref) =>
        val requestMatcher: PartialFunction[AbstractClientUserMsg[_], Unit] = 
          case PaybackRequest(amount, username) => 
            for (i,j) <- nodes do j ! PaybackRequestForward(amount, id, username)
          case CreditRequest(amount, username) => 
            for (i,j) <- nodes do j ! CreditRequestForward(amount, id, username)
        requestMatcher(req)
        Behaviors.withTimers{timer => 
          timer.startSingleTimer(SessionTimeout(id), 3.seconds) 
          inSession(id, buffer, nodes, List.empty[AddNodePoll], req, ref, f)
        }
      case _ => Behaviors.same
    }
  

  def inSession(sessionId: UUID, buffer: StashBuffer[ActorCommand], 
      nodes: HashMap[String, ActorRef[BaseServerClientNodeMsg]], 
      polls: List[AddNodePoll], request: AbstractClientUserMsg[_],
      replyTo: ActorRef[AbstractServerClientUserMsg[_]],
      f: (List[AddNodePoll], AbstractClientUserMsg[_]) => HashMap[String, BaseServerClientNodeMsg]):
      Behavior[ActorCommand] = 
    Behaviors.receiveMessagePartial{
      case command @ AddNodePoll(vote, amount, id, name) if id == sessionId =>
        val pollsUpdated = command :: polls
        if pollsUpdated.size == nodes.size then 
          f(pollsUpdated, request) match
            case responses if responses.isEmpty => 
              replyTo ! Rejected("Request declined!")
            case responses => 
              for
                (i,j) <- responses
                k <- nodes.get(i)
              do k ! j
              replyTo ! Accepted("Request accepted!")
          buffer.unstashAll(idleSession(buffer, nodes, f))
        else 
          inSession(sessionId, buffer, nodes, pollsUpdated, request, replyTo, f)
      case SessionTimeout(id) if id == sessionId =>
        replyTo ! RequestTimedout("Request timed out!")
        buffer.unstashAll(idleSession(buffer, nodes, f))
      case command => 
        inSession(sessionId, buffer.stash(command), nodes, polls, request, replyTo, f)
    }