package jkugiya.moneytransfer.api

import akka.NotUsed
import com.lightbend.lagom.scaladsl.api.{Descriptor, Service, ServiceCall}
import jkugiya.moneytransfer.api.AccountService.{Balance, Credit, Debit, DebitResult, Transfer}
import play.api.libs.json.{Format, Json}

trait AccountService extends Service {
  def debit(customerId: Int): ServiceCall[Debit, DebitResult]
  def credit(customerId: Int): ServiceCall[Credit, NotUsed]
  def get(customerId: Int): ServiceCall[NotUsed, Balance]
  def transfer: ServiceCall[Transfer, NotUsed]


  override final def descriptor: Descriptor = {
    import Service._
    named("account")
      .withCalls(
        pathCall("/api/user/:userId/debit", debit _),
        pathCall("/api/user/:userId/credit", credit _),
        pathCall("/api/user/:userId", get _),
        pathCall("/api/transfer", transfer),
      )
  }
}

object AccountService {
  case class Debit(amount: BigDecimal)
  object Debit {
    implicit val format: Format[Debit] = Json.format
  }
  case class DebitResult(isOK: Boolean)
  object DebitResult {
    implicit val format: Format[DebitResult] = Json.format
  }
  case class Credit(amount: BigDecimal)
  object Credit {
    implicit val format: Format[Credit] = Json.format
  }
  case class Balance(amount: BigDecimal)
  object Balance {
    implicit val format: Format[Balance] = Json.format
  }
  case class Transfer(from: Int, to: Int, amount: BigDecimal)
  object Transfer {
    implicit val format: Format[Transfer] = Json.format
  }
}