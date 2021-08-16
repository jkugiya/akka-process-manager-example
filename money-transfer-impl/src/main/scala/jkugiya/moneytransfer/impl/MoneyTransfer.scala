package jkugiya.moneytransfer.impl

import akka.actor.typed.Behavior
import akka.cluster.sharding.typed.scaladsl.{EntityContext, EntityTypeKey}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{
  Effect,
  EventSourcedBehavior,
  ReplyEffect
}
import com.lightbend.lagom.scaladsl.persistence.{
  AggregateEvent,
  AggregateEventTag,
  AggregateEventTagger,
  AkkaTaggerAdapter
}
import jkugiya.moneytransfer.impl.MoneyTransfer.{Command, Event, Status}
import play.api.libs.json._

import java.util.UUID

case class MoneyTransfer(id: UUID,
                         from: Int,
                         to: Int,
                         amount: BigDecimal,
                         debitResult: Option[Boolean],
                         creditResult: Option[Boolean],
                         status: Status) {

  import Command._
  def applyCommand(cmd: Command): ReplyEffect[Event, MoneyTransfer] =
    cmd match {
      case StartDebit =>
        Effect.persist(Event.DebitStarted(from, to, amount)).thenNoReply()
      case StartCredit =>
        Effect.persist(Event.CreditStarted(from, to, amount)).thenNoReply()
      case StartDebitRollback =>
        Effect
          .persist(Event.DebitRollbackStarted(from, to, amount))
          .thenNoReply()
      case ProcessDebitFail =>
        Effect.persist(Event.DebitFailed(from, to, amount)).thenNoReply()
      case ProcessDebitRollbacked =>
        Effect
          .persist(Event.DebitRollbacked(from, to, amount))
          .thenNoReply()
      case ProcessDebitRollbackFail =>
        Effect
          .persist(Event.DebitRollbackFailed(from, to, amount))
          .thenNoReply()
      case ProcessSuccess =>
        Effect.persist(Event.Succeeded(from, to, amount)).thenNoReply()
    }

  import Event._
  def applyEvent(evt: Event): MoneyTransfer = evt match {
    case DebitStarted(_, _, _) =>
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

object MoneyTransfer {
  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("money_transfer")

  sealed trait Confirmation
  object Confirmation {
    case object OK extends Confirmation
    case object NG extends Confirmation
    implicit val format: Format[Confirmation] = Format(
      Reads[Confirmation] {
        case JsObject(elems) if elems.contains("result") =>
          JsSuccess {
            elems
              .get("result")
              .map {
                case JsString("ok") => OK
                case _              => NG
              }
              .getOrElse(NG)
          }
        case _ => JsSuccess(NG)
      },
      Writes[Confirmation] {
        case OK => JsObject(Seq("result" -> JsString("ok")))
        case NG => JsObject(Seq("result" -> JsString("ng")))
      }
    )
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
  sealed trait Command
  object Command {
    case object StartDebit extends Command
    case object StartCredit extends Command
    case object StartDebitRollback extends Command
    case object ProcessDebitFail extends Command
    case object ProcessDebitRollbacked extends Command
    case object ProcessDebitRollbackFail extends Command
    case object ProcessSuccess extends Command
    implicit val format: Format[Command] =
      OFormat({
        case JsObject(kv) if kv.contains("type") =>
          kv("type") match {
            case JsString("start_debit")  => JsSuccess(StartDebit)
            case JsString("start_credit") => JsSuccess(StartCredit)
            case JsString("start_debit_rollback") =>
              JsSuccess(StartDebitRollback)
            case JsString("process_debit_fail") => JsSuccess(ProcessDebitFail)
            case JsString("process_debit_rollbacked") =>
              JsSuccess(ProcessDebitRollbacked)
            case JsString("process_debit_rollback_fail") =>
              JsSuccess(ProcessDebitRollbackFail)
            case JsString("process_success") => JsSuccess(ProcessSuccess)
            case _                           => JsError("failed to specify command")
          }
        case _ =>
          JsError("failed to specify command")
      }, {
        case StartDebit  => JsObject(Seq("type" -> JsString("start_debit")))
        case StartCredit => JsObject(Seq("type" -> JsString("start_credit")))
        case StartDebitRollback =>
          JsObject(Seq("type" -> JsString("start_debit_rollback")))
        case ProcessDebitFail =>
          JsObject(Seq("type" -> JsString("process_debit_fail")))
        case ProcessDebitRollbacked =>
          JsObject(Seq("type" -> JsString("process_debit_rollbacked")))
        case ProcessDebitRollbackFail =>
          JsObject(Seq("type" -> JsString("process_debit_rollback_fail")))
        case ProcessSuccess =>
          JsObject(Seq("type" -> JsString("process_success")))
      })
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
