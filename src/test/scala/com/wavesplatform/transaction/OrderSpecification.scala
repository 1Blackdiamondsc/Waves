package com.wavesplatform.transaction

import com.wavesplatform.TransactionGen
import com.wavesplatform.matcher.ValidationMatcher
import com.wavesplatform.state.ByteStr
import com.wavesplatform.transaction.assets.exchange.{AssetPair, Order, OrderType}
import com.wavesplatform.utils.NTP
import org.scalatest._
import org.scalatest.prop.PropertyChecks

class OrderSpecification extends PropSpec with PropertyChecks with Matchers with TransactionGen with ValidationMatcher {

  property("Order transaction serialization roundtrip") {
    forAll(orderGen) { order =>
      val recovered = Order.parseBytes(order.bytes()).get
      recovered.bytes() shouldEqual order.bytes()
      recovered.id() shouldBe order.id()
      recovered.senderPublicKey.publicKey shouldBe order.senderPublicKey.publicKey
      recovered.matcherPublicKey shouldBe order.matcherPublicKey
      recovered.assetPair shouldBe order.assetPair
      recovered.orderType shouldBe order.orderType
      recovered.price shouldBe order.price
      recovered.amount shouldBe order.amount
      recovered.timestamp shouldBe order.timestamp
      recovered.expiration shouldBe order.expiration
      recovered.matcherFee shouldBe order.matcherFee
      recovered.signature shouldBe order.signature
    }
  }

  property("Order generator should generate valid orders") {
    forAll(orderGen) { order =>
      order.isValid(NTP.correctedTime()) shouldBe valid
    }
  }

  property("Order timestamp validation") {
    forAll(orderGen) { order =>
      val time = NTP.correctedTime()
      order.copy(timestamp = -1).isValid(time) shouldBe not(valid)
    }
  }

  property("Order expiration validation") {
    forAll(arbitraryOrderGen) { order =>
      val isValid = order.isValid(NTP.correctedTime())
      val time    = NTP.correctedTime()
      whenever(order.expiration < time || order.expiration > time + Order.MaxLiveTime) {
        isValid shouldBe not(valid)
      }
    }
  }

  property("Order amount validation") {
    forAll(arbitraryOrderGen) { order =>
      whenever(order.amount <= 0) {
        order.isValid(NTP.correctedTime()) shouldBe not(valid)
      }
    }
  }

  property("Order matcherFee validation") {
    forAll(arbitraryOrderGen) { order =>
      whenever(order.matcherFee <= 0) {
        order.isValid(NTP.correctedTime()) shouldBe not(valid)
      }
    }
  }

  property("Order price validation") {
    forAll(arbitraryOrderGen) { order =>
      whenever(order.price <= 0) {
        order.isValid(NTP.correctedTime()) shouldBe not(valid)
      }
    }
  }

  property("Order signature validation") {
    forAll(orderGen, accountGen) {
      case (order, pka) =>
        order.signatureValid() shouldBe true
        order.copy(senderPublicKey = pka).signatureValid() shouldBe false
        order.copy(matcherPublicKey = pka).signatureValid() shouldBe false
        val assetPair = order.assetPair
        order
          .copy(
            assetPair = assetPair.copy(amountAsset = assetPair.amountAsset.map(Array(0: Byte) ++ _.arr).orElse(Some(Array(0: Byte))).map(ByteStr(_))))
          .signatureValid() shouldBe false
        order
          .copy(
            assetPair = assetPair.copy(priceAsset = assetPair.priceAsset.map(Array(0: Byte) ++ _.arr).orElse(Some(Array(0: Byte))).map(ByteStr(_))))
          .signatureValid() shouldBe false
        order.copy(orderType = OrderType.reverse(order.orderType)).signatureValid() shouldBe false
        order.copy(price = order.price + 1).signatureValid() shouldBe false
        order.copy(amount = order.amount + 1).signatureValid() shouldBe false
        order.copy(expiration = order.expiration + 1).signatureValid() shouldBe false
        order.copy(matcherFee = order.matcherFee + 1).signatureValid() shouldBe false
        order.copy(signature = pka.publicKey ++ pka.publicKey).signatureValid() shouldBe false
    }
  }

  property("Buy and Sell orders") {
    forAll(orderParamGen) {
      case (sender, matcher, pair, _, price, amount, timestamp, _, _) =>
        val expiration = timestamp + Order.MaxLiveTime - 1000
        val buy        = Order.buy(sender, matcher, pair, price, amount, timestamp, expiration, price)
        buy.orderType shouldBe OrderType.BUY

        val sell = Order.sell(sender, matcher, pair, price, amount, timestamp, expiration, price)
        sell.orderType shouldBe OrderType.SELL
    }
  }

  property("AssetPair test") {
    forAll(assetIdGen, assetIdGen) { (assetA: Option[AssetId], assetB: Option[AssetId]) =>
      whenever(assetA != assetB) {
        val pair = AssetPair(assetA, assetB)
        pair.isValid shouldBe valid
      }
    }
  }

}
