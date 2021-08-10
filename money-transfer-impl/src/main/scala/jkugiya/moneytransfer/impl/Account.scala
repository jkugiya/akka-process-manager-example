package jkugiya.moneytransfer.impl

import akka.Done
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.{EntityContext, EntityTypeKey}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect}
import com.lightbend.lagom.scaladsl.persistence.{AggregateEvent, AggregateEventTag, AkkaTaggerAdapter}
import jkugiya.moneytransfer.impl.Account.{Command, Credit, CreditAccepted, Debit, DebitAccepted, DebitDenied, Event}
import play.api.libs.json.{Format, Json}

case class Account(userId: Int, amount: BigDecimal) {

  def applyCommand(cmd: Command): ReplyEffect[Event, Account] = cmd match {
    case Credit(orderedAmount, ref) =>
      Effect
        .persist(CreditAccepted(userId, before = amount, amount = orderedAmount))
        .thenReply(ref)(_ => Done)
    case Debit(orderedAmount, ref) =>
      if (orderedAmount > amount) {
        Effect.persist(DebitDenied(userId))
        .thenReply(ref)(_ => Debit.Denied(amount))
      } else {
        Effect.persist(DebitAccepted(userId, before = amount, amount = orderedAmount))
          .thenReply(ref)(_ => Debit.Accepted(amount, orderedAmount))
      }
  }

  def applyEvent(event: Event): Account = event match {
    case DebitAccepted(_, before, orderedAmount) =>
      Account(userId, before - orderedAmount)
    case CreditAccepted(_, before, orderedAmount) =>
      Account(userId, before + orderedAmount)
    case DebitDenied(_) =>
      this
  }
}

object Account {
  def create(entityContext: EntityContext[Command]): Behavior[Command] = {
    val persistenceId= PersistenceId(entityContext.entityTypeKey.name, entityContext.entityId)
    val userId = entityContext.entityId.toInt
    create(userId, persistenceId)
      .withTagger(AkkaTaggerAdapter.fromLagom(entityContext, Event.Tag))
  }
  private def create(userId: Int, persistenceId: PersistenceId) =
    EventSourcedBehavior
      .withEnforcedReplies[Command, Event, Account](
        persistenceId = persistenceId,
        emptyState = Account(userId, BigDecimal(0)),
        commandHandler = (account, cmd) => account.applyCommand(cmd),
        eventHandler = (account, evt) => account.applyEvent(evt)
      )

  sealed trait Command
  case class Debit(amount: BigDecimal, ref: ActorRef[Debit.Result]) extends Command
  object Debit {
    sealed trait Result
    case class Accepted(before: BigDecimal, amount: BigDecimal) extends Result
    object Accepted {
      implicit val format: Format[Accepted] = Json.format
    }
    case class Denied(current: BigDecimal) extends Result
    object Denied {
      implicit val format: Format[Denied] = Json.format
    }
  }
  case class Credit(amount: BigDecimal, ref: ActorRef[Done]) extends Command

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("account")

  sealed trait Event extends AggregateEvent[Event] {
    def aggregateTag: AggregateEventTag[Event] = Event.Tag
  }
  object Event {
    val Tag: AggregateEventTag[Event] = AggregateEventTag[Event]
  }
  case class DebitAccepted(userId: Int, before: BigDecimal, amount: BigDecimal) extends Event
  object DebitAccepted {
    implicit val format: Format[DebitAccepted] = Json.format
  }
  case class CreditAccepted(userId: Int, before: BigDecimal, amount: BigDecimal) extends Event
  object CreditAccepted {
    implicit val format: Format[CreditAccepted] = Json.format
  }
  case class DebitDenied(userId: Int) extends Event
  object DebitDenied {
    implicit val format: Format[DebitDenied] = Json.format
  }

  implicit val format: Format[Account] = Json.format
}
