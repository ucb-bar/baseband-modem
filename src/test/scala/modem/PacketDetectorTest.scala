package modem

import chisel3._
import chisel3.util._
import chiseltest._

import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.immutable.Seq

import TestUtility._

// Note these aren't real packets, just minimal test ones
class PacketDetectorTest extends AnyFlatSpec with ChiselScalatestTester {
  it should "Test BLE packet detection" in {
    test(new BLEPacketDetector) { c =>
      c.clock.setTimeout(0)

      val space = Seq.fill(69)(true)
      val packet = rawBLEPacket(Seq(0x69))
      val signal = space ++ packet ++ space ++ packet ++ space

      c.io.aa.poke((0x8E89BED6L).U)
      c.io.eop.poke(true.B)
      c.io.in.valid.poke(true.B)
      c.clock.step()

      var num_pkts = 0
      signal.map { s =>
        c.io.eop.poke(false.B)
        c.io.in.bits.poke(s.B)
        if (c.io.sop.peek().litToBoolean)
          num_pkts += 1
        if (c.io.out.valid.peek().litToBoolean) {
          c.io.eop.poke(true.B) // only 1 byte packet
          assert(c.io.out.bits.peek().litValue == 0x69)
        }
        c.clock.step()
      }
      assert(num_pkts == 2)
    }
  }

  it should "Test LRWPAN packet detection" in {
    test(new LRWPANPacketDetector) { c =>
      c.clock.setTimeout(0)

      val space = Seq.fill(69)(true)
      val packet = chipToMSK(symbolsToChip(rawLRWPANPacket(Seq(0x69))))
      val signal = space ++ packet ++ space ++ packet ++ space

      c.io.shr.poke((0xA700).U)
      c.io.eop.poke(true.B)
      c.io.in.valid.poke(true.B)
      c.clock.step()

      var num_pkts = 0
      signal.map { s =>
        c.io.eop.poke(false.B)
        c.io.in.bits.poke(s.B)
        if (c.io.sop.peek().litToBoolean)
          num_pkts += 1
        if (c.io.out.valid.peek().litToBoolean) {
          c.io.eop.poke(true.B) // only 1 byte packet
          assert(c.io.out.bits.peek().litValue == 0x69)
        }
        c.clock.step()
      }
      assert(num_pkts == 2)
    }
  }
}
