package jkugiya.moneytransfer.impl

import com.lightbend.lagom.scaladsl.playjson.{JsonSerializer, JsonSerializerRegistry}

object MoneytransferSerializerRegistry extends JsonSerializerRegistry{
  override def serializers: Seq[JsonSerializer[_]] = Seq(
    // JsonSerializer[Account.Command], ActorRefs cant be serialized by play-json
    JsonSerializer[Account.Event.DebitAccepted],
    JsonSerializer[Account.Event.DebitDenied],
    JsonSerializer[Account.Event.TransactionAccepted],
    JsonSerializer[Account.Event.CreditAccepted],
    JsonSerializer[Account]
    // JsonSerializer[MoneyTransfer.Command], ActorRefs cant be serialized by play-json
//    JsonSerializer[MoneyTransfer.Event.DebitStarted],
//    JsonSerializer[MoneyTransfer.Event.DebitFailed],
//    JsonSerializer[MoneyTransfer.Event.CreditStarted],
//    JsonSerializer[MoneyTransfer.Event.DebitRollbackStarted],
//    JsonSerializer[MoneyTransfer.Event.DebitRollbackFailed],
//    JsonSerializer[MoneyTransfer.Event.DebitRollbacked],
//    JsonSerializer[MoneyTransfer.Event.Succeeded],
//    JsonSerializer[MoneyTransfer]
  )
}
