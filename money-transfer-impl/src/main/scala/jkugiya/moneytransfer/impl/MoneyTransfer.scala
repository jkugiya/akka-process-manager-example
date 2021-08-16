package jkugiya.moneytransfer.impl

import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.{EntityContext, EntityTypeKey}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect}
import com.lightbend.lagom.scaladsl.persistence.{AggregateEvent, AggregateEventTag, AggregateEventTagger, AkkaTaggerAdapter}
import jkugiya.moneytransfer.impl.MoneyTransfer.{Command, Confirmation, Event, Status}
import org.slf4j.LoggerFactory
import play.api.libs.json._

import java.util.UUID

case class MoneyTransfer(id: UUID,
                         from: Int,
                         to: Int,
                         amount: BigDecimal,
                         debitResult: Option[Boolean],
                         creditResult: Option[Boolean],
                         status: Status) {

  private val logger = LoggerFactory.getLogger(getClass)

  import Command._
  def applyCommand(cmd: Command): ReplyEffect[Event, MoneyTransfer] =
    cmd match {
      case StartTransfer(from, to, amount, ref) =>
        logger.info(s"Started Transfer.")
        Effect.persist(Event.DebitStarted(from, to, amount)).thenReply(ref)(_ => Confirmation.OK)
      case StartCredit(ref: ActorRef[Confirmation]) =>
        logger.info(s"Started Credit.")
        Effect.persist(Event.CreditStarted(from, to, amount)).thenReply(ref)(_ => Confirmation.OK)
      case StartDebitRollback(ref: ActorRef[Confirmation]) =>
        logger.info(s"Started DebitRollback.")
        Effect
          .persist(Event.DebitRollbackStarted(from, to, amount))
          .thenReply(ref)(_ => Confirmation.OK)
      case ProcessDebitFail(ref: ActorRef[Confirmation]) =>
        logger.info(s"Debit failed")
        Effect.persist(Event.DebitFailed(from, to, amount)).thenReply(ref)(_ => Confirmation.OK)
      case ProcessDebitRollbacked(ref: ActorRef[Confirmation]) =>
        logger.info(s"Debit Rollbacked")
        Effect
          .persist(Event.DebitRollbacked(from, to, amount))
          .thenReply(ref)(_ => Confirmation.OK)
      case ProcessDebitRollbackFail(ref: ActorRef[Confirmation]) =>
        logger.info(s"DebitRollback failed")
        Effect
          .persist(Event.DebitRollbackFailed(from, to, amount))
          .thenReply(ref)(_ => Confirmation.OK)
      case ProcessSuccess(ref: ActorRef[Confirmation]) =>
        logger.info(s"MoneyTransfer Succeeded.")
        Effect.persist(Event.Succeeded(from, to, amount)).thenReply(ref)(_ => Confirmation.OK)
    }

  import Event._
  def applyEvent(evt: Event): MoneyTransfer = evt match {
    case DebitStarted(from, to, amount) =>
      MoneyTransfer(
        id = id,
        from = from,
        to = to,
        amount = amount,
        debitResult = None,
        creditResult = None,
        status = Status.DebitStarted
      )
    case DebitFailed(_, _, _) =>
      MoneyTransfer(
        id = id,
        from = from,
        to = to,
        amount = amount,
        debitResult = Option(false),
        creditResult = None,
        status = Status.DebitFailed
      )
    case CreditStarted(_, _, _) =>
      MoneyTransfer(
        id = id,
        from = from,
        to = to,
        amount = amount,
        debitResult = Option(true),
        creditResult = None,
        status = Status.CreditStarted
      )
    case DebitRollbackStarted(_, _, _) =>
      MoneyTransfer(
        id = id,
        from = from,
        to = to,
        amount = amount,
        debitResult = Option(true),
        creditResult = Option(false),
        status = Status.DebitRollbackStarted
      )
    case DebitRollbacked(_, _, _) =>
      MoneyTransfer(
        id = id,
        from = from,
        to = to,
        amount = amount,
        debitResult = Option(true),
        creditResult = Option(false),
        status = Status.DebitRollbacked
      )
    case DebitRollbackFailed(_, _, _) =>
      MoneyTransfer(
        id = id,
        from = from,
        to = to,
        amount = amount,
        debitResult = Option(true),
        creditResult = Option(false),
        status = Status.DebitRollbackFailed
      )
    case Succeeded(_, _, _) =>
      MoneyTransfer(
        id = id,
        from = from,
        to = to,
        amount = amount,
        debitResult = Option(true),
        creditResult = Option(true),
        status = Status.Succeeded
      )
  }
}

sealed trait MoneyTransferMessageSerializable
object MoneyTransfer {
  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("money_transfer")

  sealed trait Confirmation extends MoneyTransferMessageSerializable
  object Confirmation {
    case object OK extends Confirmation
  }

  sealed trait Status
  object Status {

    case object Init extends Status
    case object DebitStarted extends Status
    case object CreditStarted extends Status
    case object DebitRollbackStarted extends Status
    case object DebitRollbacked extends Status
    case object DebitRollbackFailed extends Status
    case object DebitFailed extends Status
    case object Succeeded extends Status

    implicit val format: Format[Status] = Format(Reads[Status] {
      case JsString("init")                   => JsSuccess(Init)
      case JsString("debit_started")          => JsSuccess(DebitStarted)
      case JsString("credit_started")         => JsSuccess(CreditStarted)
      case JsString("debit_rollback_started") => JsSuccess(DebitRollbackStarted)
      case JsString("debit_failed")           => JsSuccess(DebitFailed)
      case JsString("debit_rollbacked")       => JsSuccess(DebitRollbacked)
      case JsString("debit_rollback_failed")  => JsSuccess(DebitRollbackFailed)
      case JsString("succeeded")              => JsSuccess(Succeeded)
      case _                                  => JsError()
    }, Writes[Status] {
      case Init                 => JsString("init")
      case DebitStarted         => JsString("debit_started")
      case CreditStarted        => JsString("credit_started")
      case DebitRollbackStarted => JsString("debit_rollback_started")
      case DebitFailed          => JsString("debit_failed")
      case DebitRollbacked      => JsString("debit_rollbacked")
      case DebitRollbackFailed  => JsString("debit_rollback_failed")
      case Succeeded            => JsString("succeeded")
    })
  }
  sealed trait Command extends MoneyTransferMessageSerializable
  object Command {
    case class StartTransfer(from: Int, to: Int, amount: BigDecimal, ref: ActorRef[Confirmation]) extends Command
    case class StartCredit(ref: ActorRef[Confirmation]) extends Command
    case class StartDebitRollback(ref: ActorRef[Confirmation]) extends Command
    case class ProcessDebitFail(ref: ActorRef[Confirmation]) extends Command
    case class ProcessDebitRollbacked(ref: ActorRef[Confirmation]) extends Command
    case class ProcessDebitRollbackFail(ref: ActorRef[Confirmation]) extends Command
    case class ProcessSuccess(ref: ActorRef[Confirmation]) extends Command
  }

  sealed trait Event extends AggregateEvent[Event] {
    override def aggregateTag: AggregateEventTagger[Event] = Event.Tag
  }
  object Event {
    case class DebitStarted(from: Int, to: Int, amount: BigDecimal)
        extends Event
    object DebitStarted {
      implicit val format: Format[DebitStarted] = Json.format
    }
    case class DebitFailed(from: Int, to: Int, amount: BigDecimal) extends Event
    object DebitFailed {
      implicit val format: Format[DebitFailed] = Json.format
    }
    case class CreditStarted(from: Int, to: Int, amount: BigDecimal)
        extends Event
    object CreditStarted {
      implicit val format: Format[CreditStarted] = Json.format
    }
    case class DebitRollbackStarted(from: Int, to: Int, amount: BigDecimal)
        extends Event
    object DebitRollbackStarted {
      implicit val format: Format[DebitRollbackStarted] = Json.format
    }
    case class DebitRollbackFailed(from: Int, to: Int, amount: BigDecimal)
        extends Event
    object DebitRollbackFailed {
      implicit val format: Format[DebitRollbackFailed] = Json.format
    }
    case class DebitRollbacked(from: Int, to: Int, amount: BigDecimal)
        extends Event
    object DebitRollbacked {
      implicit val format: Format[DebitRollbacked] = Json.format
    }
    case class Succeeded(from: Int, to: Int, amount: BigDecimal) extends Event
    object Succeeded {
      implicit val format: Format[Succeeded] = Json.format
    }
    val Tag: AggregateEventTag[Event] = AggregateEventTag[Event]
  }

  def create(entityContext: EntityContext[Command]): Behavior[Command] = {
    val persistenceId =
      PersistenceId(entityContext.entityTypeKey.name, entityContext.entityId)
    val id = UUID.fromString(entityContext.entityId)
    create(id, persistenceId)
      .withTagger(AkkaTaggerAdapter.fromLagom(entityContext, Event.Tag))
  }

  private def create(id: UUID, persistenceId: PersistenceId) =
    EventSourcedBehavior
      .withEnforcedReplies[Command, Event, MoneyTransfer](
        persistenceId = persistenceId,
        emptyState = MoneyTransfer(
          id = id,
          from = 0,
          to = 0,
          amount = BigDecimal(0),
          debitResult = None,
          creditResult = None,
          status = Status.Init
        ),
        commandHandler = (transfer, cmd) => transfer.applyCommand(cmd),
        eventHandler = (transfer, evt) => transfer.applyEvent(evt)
      )

  implicit val format: Format[MoneyTransfer] = Json.format
}
