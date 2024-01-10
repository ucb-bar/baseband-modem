



The LE system uses 40 RF channels. These RF channels have center frequencies 2402 + k * 2 MHz, where k = 0, ..., 39.



### Modulation Characteristics

The modulation index shall be between 0.45 and 0.55. A binary one shall be represented by a positive frequency deviation, and a binary zero shall be represented by a negative frequency deviation.

An LE device with a transmitter that has a stable modulation index may inform the receiving LE device of this fact through the feature support mechanism (see [Vol 6] Part B, Section 4.6). The modulation index for these transmitters shall be between 0.495 and 0.505. A device shall only state that it has a stable modulation index if that applies to all LE transmitter PHYs it supports.

A transmitter that does not have a stable modulation index is said to have a standard modulation index.

#### RF Tolerance

The deviation of the center frequency during the packet shall not exceed ±150 kHz, including both the initial frequency offset and drift. The frequency drift during any packet shall be less than 50 kHz. The drift rate shall be less than 400 Hz/μs.

The reference signal for LE is defined as: Modulation = GFSK

Modulation index = 0.5 ± 1% for standard modulation index, 0.5 ± 0.5% for stable modulation index

BT = 0.5 ± 1% Data Bit Rate =

- 1Mb/s±1ppmfortheLE1MPHY

### Bluetooth Host

The Bluetooth Specification defines the Logical Link Control and Adaptation Layer Protocol, referred to as L2CAP. L2CAP provides connection- oriented and connectionless data services to upper layer protocols with protocol multiplexing capability and segmentation and reassembly operation. L2CAP permits higher level protocols and applications to transmit and receive upper layer data packets (L2CAP Service Data Units, SDU) up to 64 kilobytes in length. L2CAP also permits per-channel flow control and retransmission. The L2CAP layer provides logical channels, named L2CAP channels that are multiplexed over one or more logical links. [Core Spec. v5.2, Vol 3, Part A]

L2CAP sits above a lower layer, in our case this lower layer is an LE controller that supports LE only.

### BLE Link Layer

#### State Machine

The BLE Link Layer can be described as a state machine with the following states:

- Standby State
- Advertising State
- Scanning State
- Initiating State
- Connection State
- Synchronization State
- Isochronous Broadcasting State

Only one state can be active at a given time. The Link Layer **must** have one of these state machines that supports either the Advertising state or the Scanning state. A Link Layer can have multiple instance of the Link Layer State Machine.

### Bit Ordering

The Least Significant Bit (LSB) is sent over the air first. Multi-octet fields with the excetion of the CRC and MIC, shall be transmitted with the least significant octet first. Each octet within multi-octet fields with the exception of the CRC, shall be transmitted in LSB first order.


### Physical Channels

There are 40 RF channels. These RF channels are divided into:

- 3 RF channels known as the "primary advertising channels" (used for initial advertising and legacy advertising activities),
- and 37 RF channels known as the "general-purpose channels" 

These two groups of RF channels are used in four LE physical channels:

- advertising: uses both RF channel groups for discovering devices, initiating a connection, and broadcasting data
- periodic: only uses general-purpose channels
- isochronous: only uses general-purpose channels
- data: only uses general-purpose channels

Two devices that wish to communicate use a shared physical channel. To achieve this, their transceivers must be tuned to the same RF channel at the same time.

Given that the number of RF channels is limited, and that many Bluetooth devices may be operating independently within the same spatial and temporal area, there is a strong likelihood of two independent Bluetooth devices having their transceivers tuned to the same RF channel, resulting in a physical channel collision. To mitigate the unwanted effects of this collision, each transmission on a physical channel starts with an Access Address that is used as a correlation code by devices tuned to the physical channel. This Access Address is a property of the physical channel.

The Link Layer uses one physical channel at a given time. Whenever the Link Layer is synchronized to the timing, frequency, and Access Address of a physical channel, it is said to be 'connected' on the data physical channel or ‘synchronized’ to the periodic physical channel or isochronous physical channel.

The Physical Channel Index does **NOT** directly reflect the PHY channel index and the RF center frequency. (e.g. channel index 37 [part of the primary advertising RF channel group] corresponds to the PHY channel of 0, and an RF center frequency of 2402MHz). A full mapping can be found in Core v5.2 Vol 6, Part B (page 2864).

### Air Interface Packets

There are two basic packet formats: LE Uncoded PHY, and LE Coded PHY. We are only implementing LE Uncoded PHYs. 

![Screen Shot 2021-03-10 at 2.15.31 PM](/Users/griffinprechter/Desktop/Screen Shot 2021-03-10 at 2.15.31 PM.png)

Each packet has 4 mandatory fields, and one optional field. The Preamble, Access Address, PDU, and CRC are mandatory fields. The Constant Tone Extension is optional.

For the sake of **our** controller, we will only be supporting LE 1M (1Msym/s), the preamble shall only be 1 octet.

#### Preamble

All Link Layer packets have a preamble which is used in the receiver to perform frequency synchronization, symbol timing estimation, and Automatic Gain Control (AGC) training. The preamble is a fixed sequence of alternating 0 and 1 bits. The first bit of the preamble (in transmission order) shall be the same as the LSB of the Access Address.

#### Access Address

The Access Address shall be a 32-bit value. This value shall be set by the CPU and will be used by the next transmission or reception.

#### PDU

When a packet is transmitted on either the primary or secondary advertising physical channel or the periodic physical channel, the PDU shall be the Advertising Physical Channel PDU. When a packet is transmitted on the data physical channel, the PDU shall be the Data Physical Channel PDU. When a packet is transmitted on the isochronous physical channel, the PDU shall be one of the Isochronous Physical Channel PDU.

#### CRC

The CRC is 24 bits and it is calculated over the **PDU**, and the CRC polynomial is defined later.

#### Constant Tone Extension

The constant tone extension is a series of **unwhitened** 1s. It is **not** included in the CRC or MIC. The constant tone exentsion shall not be presented in a packet sent on the isochronous physical channel.

### PDU Types

#### Advertising Physical Channel PDU

Uses a combination of the primary advertising physical channel, and secondary advertising physical channel, as well as the periodic physical channel.

##### Advertising Physical Channel PDU Format

![Screen Shot 2021-03-10 at 2.38.38 PM](/Users/griffinprechter/Desktop/Screen Shot 2021-03-10 at 2.38.38 PM.png)

##### Advertising Physical Channel PDU Header Format

![Screen Shot 2021-03-10 at 2.38.49 PM](/Users/griffinprechter/Desktop/Screen Shot 2021-03-10 at 2.38.49 PM.png)

Some advertising physical channel PDUs contain an `AuxPtr` field that points to a packet containing another advertising physical channel PDU. Given a packet it's "subortindate set" consts of it's auxiliary packet.

#### Data Physical Channel PDU

The Data Physical Channel PDU has a 16 or 24 bit header, a variable size payload, and may include a Message Integrity Check (MIC) field.

##### Data Physical Channel PDU Format

<img src="/Users/griffinprechter/Desktop/Screen Shot 2021-03-10 at 3.10.16 PM.png" alt="Screen Shot 2021-03-10 at 3.10.16 PM" style="zoom:50%;" />

##### Data Physical Channel PDU Header Format

![Screen Shot 2021-03-10 at 3.10.29 PM](/Users/griffinprechter/Desktop/Screen Shot 2021-03-10 at 3.10.29 PM.png)



The MIC field shall not be included in an un-encrypted ACL connection, or in an encrypted ACL connection with a Data Channel PDU with a zero length Payload.

The CTEInfo Present (CP) field of the Header indicates whether the Data Physical Channel PDU Header has a CTEInfo field and therefore whether the data physical channel packet has a Constant Tone Extension. If the CP field is 0, then no CTEInfo field is present in the Data Channel PDU Header and there is no Constant Tone Extension in the data physical channel packet. If the CP field is 1, then the CTEInfo field in the Data Physical Channel PDU Header is present and the data physical channel packet includes a Constant Tone Extension.

The Length field of the Header indicates the length of the Payload and MIC if included. The length field has the range 0 to 255 octets. The Payload shall be less than or equal to 251 octets in length. The MIC is 4 octets in length.

#### IQ Sampling

This is how CTE is used.

#### Isochronous Physical Channel PDU

 ##### Isochronous Physical Channel PDU Format

![Screen Shot 2021-03-10 at 4.08.28 PM](/Users/griffinprechter/Desktop/Screen Shot 2021-03-10 at 4.08.28 PM.png)

##### Connected Isochronous PDU Header Format

![Screen Shot 2021-03-10 at 4.08.41 PM](/Users/griffinprechter/Desktop/Screen Shot 2021-03-10 at 4.08.41 PM.png)

##### Broadcast Isochronous PDU Header Format

![Screen Shot 2021-03-10 at 4.08.59 PM](/Users/griffinprechter/Desktop/Screen Shot 2021-03-10 at 4.08.59 PM.png)

## Bit Stream Processing

![Screen Shot 2021-03-10 at 4.14.00 PM](/Users/griffinprechter/Desktop/Screen Shot 2021-03-10 at 4.14.00 PM.png)

#### Error Checking

On reception, the Access Address shall be checked first. If the access address is incorrect, the packet shall be rejected, otherwise the packet shall be considered received. If the CRC is incorrect, the packet shall be rejected, otherwise the packet shall be considered successuflly recieved and therefore valid. A packet shall only be processed if the packet is considered valid, except that the receiver may carry out IQ sampling even if the CRC is incorrect. 

During a connection event, the master and slave alternate sending and receiving packets. The connection event is considered open while both devices continue to send packets. The slave shall always send a packet if it receives a packet from the master regardless of a valid CRC match, except after multiple consecutive invalid CRC matches as specified in Section 4.5.6. The master may send a packet if it receives a packet from the slave regardless of a valid CRC match, except after multiple consecutive invalid CRC matches as specified in Section 4.5.6. When determining the end of the received packet, the Length and CP fields of the Header, and the CTETime field of the CTEInfo field (if present), are assumed to be correct even if the CRC match was invalid; however, if the receiving device can determine the correct Length and CTETime in some other way, it may use those values instead of those in the Header.

#### CRC Generation

The CRC shall be calculated on the PDU of all Link Layer packets. CRC is calculated *after* encryption, if the packet is encrypted. The CRC polynomial is 24-bits and has the form $x^{24} + x^{10} + x^9 + x^6 + x^4 + x^3 + x + 1$.

The preset value for the CRC can be set by the CPU. The CRC is transmitted MOST SIGNIFICANT BIT FIRST, from position 23 to position 0.

##### The LFSR Circuit generating the CRC

![Screen Shot 2021-03-10 at 4.22.58 PM](/Users/griffinprechter/Desktop/Screen Shot 2021-03-10 at 4.22.58 PM.png)

#### Data Whitening

Data whitening is used to avoid long sequences of zeros or ones in the data bit stream. Whitening shall be applied on the PDU and CRC of all Link Layer packets and is performed after the CRC generation in the transmitter. Note: **no other parts of the packets are whitened**

Dewhitening is performed before the CRC checking in the receiver. The whitener and de-whitener are defined the same way, using and LFSR with the polynomial $x^7 + x^4 + 1$. The LFSR is initialized with a sequence that is derrived from the **physical channel index**, recall that this index is **not** the index used to tune the RF center frequency. The LFSR is initialized as follows:

- Position 0 is set to one.
- Positions 1 to 6 are set to the channel index used when transmitting or receiving, from the most significant bit in position 1 and the least significant bit in position 6.

##### LFSR For Data Whitening

![Screen Shot 2021-03-10 at 4.34.03 PM](/Users/griffinprechter/Desktop/Screen Shot 2021-03-10 at 4.34.03 PM.png)

### Timing Requriements

The average timing of packet transmission during a connection, BIG, or CIG event, during active scanning, and when requesting a connection is determined using the **active clock accuracy**, with a drift less than or equal to ±50 ppm. All instantaneous timings shall not deviate more than 2 μs from the average timing. It's optional to have a less accurate "sleep clock", but the active clock is fine.

