# 2.4 GHz BLE & IEEE 802.15.4 Baseband-Modem

This is a baseband-modem processor designed to be used as a peripheral on an SoC generated with Chipyard. It supports both 2.4 GHz Bluetooth Low Energy and IEEE 802.15.4 specifications, and requires an analog radio front-end that can either be on-chip or off-chip. 

For Bluetooth Low Energy, this processor handles Bluetooth Low Energy 1M Uncoded Link Layer Packets. And for the IEEE 802.15.4 2.4 GHz PHY, this processor prepares, transmits, receives, and unpacks packets. Additionally the processor exposes an interface for the CPU to read/write various information (e.g. tuning bits) to the analog radio front-end.

## Documentation

[Full technical documentation](https://ucb-bar.github.io/baseband-modem/) for this project is hosted on GitHub Pages and built from AsciiDoc source in the `doc/` directory.

The full technical documentation includes:
 - Register map enumeration
 - Interrupt enumeration
 - Baseband processor miniature ISA specification
 - Configuration guides for the LUTs
 - Debug & loopback operation guide
 - Explanation of theory of operation
 - Example on-chip radio front-end specification & architecture

## Repository Structure

- `src/main/scala/`
    - `baseband/`: Source code for the baseband related functionality (i.e. packet assembly, disassembly, symbol spreading, and whitening)
    - `modem/`: Source code for the modem related functionality (i.e. modulation, demodulation, clock & data recovery, matched filters)


## Usage

This project is designed to be used as a peripheral on an SoC generated with Chipyard, and this usage guide assumed you have started with a fork of the Chipyard repository.

If this guide makes little sense, it's best to begin with the Chipyard documentation. Specifically these two guides are a good start:

- [Chipyard Documentation - MMIO Peripherals](https://chipyard.readthedocs.io/en/stable/Customization/MMIO-Peripherals.html)
- [Chipyard Documentation - IOBinders and HarnessBindres](https://chipyard.readthedocs.io/en/stable/Customization/IOBinders.html)

### Chipyard root build.sbt

Add this project as a dependency by adding the following lines to the Chipyard root `build.sbt` file:

Ensure that the `chipyard` project depends on the `baseband` project as well as the `dma` project (which is a dependency of the `baseband` project).

```scala
lazy val chipyard = (project in file("generators/chipyard"))
  .dependsOn(testchipip, rocketchip, boom, hwacha, sifive_blocks, sifive_cache, iocell,
    sha3,
    dsptools, `rocket-dsp-utils`,
    gemmini, icenet, tracegen, cva6, nvdla, sodor, ibex, fft_generator,
    constellation, mempress, dma, baseband) // Add the baseband project as a dependency
  .settings(libraryDependencies ++= rocketLibDeps.value)
  .settings(commonSettings)
```

Then include the `baseband` project in the `build.sbt` file. 

```scala
lazy val baseband = (project in file("generators/baseband"))
  .dependsOn(dma)
  .settings(chiselSettings)
  .settings(commonSettings)
```

### Chipyard ChipConfigs.scala
In your `generators/chipyard/src/main/scala/config/ChipConfigs.scala` file, you should have your own extension
of the Config that parametrizes your SoC. 

To include the baseband-modem peripheral:

```scala
// BEGIN: Baseband-modem peripheral settings
new baseband.WithBasebandModem() ++
new chipyard.iobinders.WithBasebandModemPunchthrough() ++
new chipyard.harness.WithBasebandModemTiedOff ++
// END: Baseband-modem peripheral settings
```

### Chipyard HarnessBinders.scala
In your `generators/chipyard/src/main/scala/HarnessBinders.scala` file, an example configuration of the harness binder is given below. This is for a 32 MHz ADC clock.

```scala
import baseband.{CanHavePeripheryBasebandModem, BasebandModemAnalogIO}

class WithBasebandModemTiedOff extends OverrideHarnessBinder({
  (system: CanHavePeripheryBasebandModem, th: HasHarnessSignalReferences, ports: Seq[Data]) => {
    implicit val p = chipyard.iobinders.GetSystemParameters(system)
    val basebandClockFreqMHz: Double = 32.0
    val basebandClkBundle = p(HarnessClockInstantiatorKey).requestClockBundle("baseband_adc_clock", basebandClockFreqMHz * (1000 * 1000))
    ports.map {
      case p: BasebandModemAnalogIO =>
        p.data.rx.i.data := 0.U
        p.data.rx.q.data := 0.U
        p.data.rx.i.valid := 0.U
        p.data.rx.q.valid := 0.U
        p.tuning.trim.g1 := 0.U
        p.adc_clock := basebandClkBundle.clock
    }
  }
})
```

### Chipyard IOBinders.scala

In your `generators/chipyard/src/main/scala/IOBinders.scala` file, an example configuration of the IO binder is given below. Nothing in this case is specific to the DSP/ADC frequency, but it does leverage the use of the `offchipmode_rx/tx` IO signals associated with half-duplex switches in the RFE.

```scala
import baseband.{CanHavePeripheryBasebandModem, BasebandModemAnalogIO, BasebandModemParams}

class WithBasebandModemPunchthrough(params: BasebandModemParams = BasebandModemParams()) extends OverrideIOBinder({
  (system: CanHavePeripheryBasebandModem) => {
    val sys = system.asInstanceOf[BaseSubsystem]

    val ports: Seq[BasebandModemAnalogIO] = system.baseband.map({ a =>
      val analog = IO(new BasebandModemAnalogIO(params)).suggestName("baseband")
      analog <> a
      analog
    }).toSeq

    val (_, offchipmode_rx) = IOCell.generateIOFromSignal(system.baseband.get.offChipMode.rx, s"baseband__offChipMode_rx", Intech22IOCellParams(eastWest=false))
    val (_, offchipmode_tx) = IOCell.generateIOFromSignal(system.baseband.get.offChipMode.tx, s"baseband__offChipMode_tx", Intech22IOCellParams(eastWest=false))

    ((ports), (offchipmode_rx ++ offchipmode_tx))
  }
})
```
