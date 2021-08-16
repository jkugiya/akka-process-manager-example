package jkugiya.moneytransfer.impl

import com.lightbend.lagom.scaladsl.playjson.{JsonSerializer, JsonSerializerRegistry}

object MoneytransferSerializerRegistry extends JsonSerializerRegistry{
  override def serializers: Seq[JsonSerializer[_]] = Seq(
    JsonSerializer[Account.Command.Debit.Denied],
    JsonSerializer[Account.Command.Debit.Accepted],
    JsonSerializer[Account.Event.DebitAccepted],
    JsonSerializer[Account.Event.DebitDenied],
    JsonSerializer[Account.Event.CreditAccepted],
    JsonSerializer[Account],
    JsonSerializer[MoneyTransfer.Command],
    JsonSerializer[MoneyTransfer.Event.CreditStarted],
    JsonSerializer[MoneyTransfer.Event.DebitStarted],
    JsonSerializer[MoneyTransfer.Event.DebitRollbackStarted],
    JsonSerializer[MoneyTransfer.Event.Succeeded],
    JsonSerializer[MoneyTransfer.Event.DebitFailed],
    JsonSerializer[MoneyTransfer.Event.DebitRollbackFailed],
    JsonSerializer[MoneyTransfer]
  )
}
