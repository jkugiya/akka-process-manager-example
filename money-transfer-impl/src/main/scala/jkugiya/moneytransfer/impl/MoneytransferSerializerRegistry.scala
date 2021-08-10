package jkugiya.moneytransfer.impl

import com.lightbend.lagom.scaladsl.playjson.{JsonSerializer, JsonSerializerRegistry}

object MoneytransferSerializerRegistry extends JsonSerializerRegistry{
  override def serializers: Seq[JsonSerializer[_]] = Seq(
    JsonSerializer[Account.Debit.Denied],
    JsonSerializer[Account.Debit.Accepted],
    JsonSerializer[Account.DebitAccepted],
    JsonSerializer[Account.DebitDenied],
    JsonSerializer[Account.CreditAccepted],
    JsonSerializer[Account],
  )
}
