package simpledistributedconsensus

import scodec.Codec
import scodec.codecs._
import java.util.UUID

object Protocol:

  object ClientUserMsg:

    /** Base trait for messages sent from the client to the server. */
    sealed trait AbstractClientUserMsg[T]:
      val msg: T
    
    case class PaybackRequest(msg: Int, username: String) extends AbstractClientUserMsg[Int]
    case class CreditRequest(msg: Int, username: String) extends AbstractClientUserMsg[Int]
    case class QuitUser(msg: Boolean) extends AbstractClientUserMsg[Boolean]

    private val codec: Codec[AbstractClientUserMsg[_]] = discriminated[AbstractClientUserMsg[_]]
      .by(uint8)
      .typecase(2, (uint8 :: utf8_32).as[PaybackRequest])
      .typecase(3, (uint8 :: utf8_32).as[CreditRequest])
      .typecase(4, bool.as[QuitUser])
    
    val encoder = ScodecGlue.encoder(codec)
    val decoder = ScodecGlue.decoder(codec)


  object ClientNodeMsg:

    /** Base trait for messages sent from the node to the server. */
    sealed trait AbstractClientNodeMsg[T]:
      val msg: T

    case class Join(msg: String) extends AbstractClientNodeMsg[String]
    case class Poll(msg: Vote, amount: Option[Int], sessonId: UUID, nodename: String) extends AbstractClientNodeMsg[Vote]

    sealed trait Vote
    case object VoteIn extends Vote
    case object VoteOut extends Vote

    private val voteCodec: Codec[Vote] = discriminated[Vote]
      .by(uint8)
      .typecase(1, provide(VoteIn))
      .typecase(2, provide(VoteOut))

    private val codec: Codec[AbstractClientNodeMsg[_]] = discriminated[AbstractClientNodeMsg[_]]
      .by(uint8)
      .typecase(3, utf8_32.as[Join])
      .typecase(4, (voteCodec :: optional(bool(2), int32) :: uuid :: utf8_32).as[Poll])

    val encoder = ScodecGlue.encoder(codec)
    val decoder = ScodecGlue.decoder(codec)

      
  object ServerClientUserMsg:

    /** Base trait for messages sent from the server to the client */
    sealed trait AbstractServerClientUserMsg[T]:
      val msg: T

    case class Welcome(msg: String) extends AbstractServerClientUserMsg[String]
    case class Accepted(msg: String) extends AbstractServerClientUserMsg[String]
    case class Rejected(msg: String) extends AbstractServerClientUserMsg[String]
    case class RequestTimedout(msg: String) extends AbstractServerClientUserMsg[String]

    private val codec: Codec[AbstractServerClientUserMsg[_]] = discriminated[AbstractServerClientUserMsg[_]]
      .by(uint8)
      .typecase(1, utf8_32.as[Welcome])
      .typecase(2, utf8_32.as[Accepted])
      .typecase(3, utf8_32.as[Rejected])
      .typecase(3, utf8_32.as[RequestTimedout])
    
    val encoder = ScodecGlue.encoder(codec)
    val decoder = ScodecGlue.decoder(codec)


  object ServerClientNodeMsg:

    /** Base trait for messages sent from the server to the node.
      * This is in addition to the Payback and Credit requests received
      * by the server from the client which will be forwarded to the node.
     */
    sealed trait BaseServerClientNodeMsg
    case class PaybackRequestForward(amount: Int, sessonId: UUID, username: String) extends BaseServerClientNodeMsg
    case class CreditRequestForward(amount: Int, sessonId: UUID, username: String) extends BaseServerClientNodeMsg
    case class PaybackSplit(amount: Int, username: String) extends BaseServerClientNodeMsg
    case class CreditSplit(amount: Int, username: String) extends BaseServerClientNodeMsg

    private val codec: Codec[BaseServerClientNodeMsg] = discriminated[BaseServerClientNodeMsg]
      .by(uint8)
      .typecase(2, (int32 :: uuid :: utf8_32).as[PaybackRequestForward])
      .typecase(3, (int32 :: uuid :: utf8_32).as[CreditRequestForward])
      .typecase(4, (int32 :: utf8_32).as[PaybackSplit])
      .typecase(5, (int32 :: utf8_32).as[CreditSplit])
  
    val encoder = ScodecGlue.encoder(codec)
    val decoder = ScodecGlue.decoder(codec)    