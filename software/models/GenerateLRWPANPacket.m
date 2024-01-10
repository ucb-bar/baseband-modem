% Adapted from Matlab's LR-WPAN toolbox (PHYGeneratorOQPSK)
%   WAVEFORM = PHYGENERATOROQPSK( message ) uses 16-ary offset QPSK
%   (O-QPSK) to generate the physical-layer waveform WAVEFORM corresponding
%   to the MAC protocol data unit message. A synchronization header is added,
%   comprising a preamble and a "start-of-frame" delimiter. The frame
%   length is also encoded. The message bits are spreaded to chips, which are
%   subsequently O-QPSK modulated and filtered.
%
%   See also LRWPAN.PHYDECODEROQPSK, LRWPAN.PHYDECODEROQPSKNOSYNC, LRWPAN.PHYGENERATORBPSK,
%   LRWPAN.PHYGENERATORASK, LRWPAN.PHYGENERATORGFSK

%   Copyright 2017-2020 The MathWorks, Inc.

function [ppdu, chips] = GenerateLRWPANPacket(message)
reservedValue = 0;
band = '2450 MHz';

if strcmp(band, '2450 MHz')
  chipLen = 32;    
  % See Table 73 in IEEE 802.15.4,  2011 revision
  chipMap = ...
     [1 1 0 1 1 0 0 1 1 1 0 0 0 0 1 1 0 1 0 1 0 0 1 0 0 0 1 0 1 1 1 0;
      1 1 1 0 1 1 0 1 1 0 0 1 1 1 0 0 0 0 1 1 0 1 0 1 0 0 1 0 0 0 1 0;
      0 0 1 0 1 1 1 0 1 1 0 1 1 0 0 1 1 1 0 0 0 0 1 1 0 1 0 1 0 0 1 0;
      0 0 1 0 0 0 1 0 1 1 1 0 1 1 0 1 1 0 0 1 1 1 0 0 0 0 1 1 0 1 0 1;
      0 1 0 1 0 0 1 0 0 0 1 0 1 1 1 0 1 1 0 1 1 0 0 1 1 1 0 0 0 0 1 1;
      0 0 1 1 0 1 0 1 0 0 1 0 0 0 1 0 1 1 1 0 1 1 0 1 1 0 0 1 1 1 0 0;
      1 1 0 0 0 0 1 1 0 1 0 1 0 0 1 0 0 0 1 0 1 1 1 0 1 1 0 1 1 0 0 1;
      1 0 0 1 1 1 0 0 0 0 1 1 0 1 0 1 0 0 1 0 0 0 1 0 1 1 1 0 1 1 0 1;
      1 0 0 0 1 1 0 0 1 0 0 1 0 1 1 0 0 0 0 0 0 1 1 1 0 1 1 1 1 0 1 1;
      1 0 1 1 1 0 0 0 1 1 0 0 1 0 0 1 0 1 1 0 0 0 0 0 0 1 1 1 0 1 1 1;
      0 1 1 1 1 0 1 1 1 0 0 0 1 1 0 0 1 0 0 1 0 1 1 0 0 0 0 0 0 1 1 1;
      0 1 1 1 0 1 1 1 1 0 1 1 1 0 0 0 1 1 0 0 1 0 0 1 0 1 1 0 0 0 0 0;
      0 0 0 0 0 1 1 1 0 1 1 1 1 0 1 1 1 0 0 0 1 1 0 0 1 0 0 1 0 1 1 0;
      0 1 1 0 0 0 0 0 0 1 1 1 0 1 1 1 1 0 1 1 1 0 0 0 1 1 0 0 1 0 0 1;
      1 0 0 1 0 1 1 0 0 0 0 0 0 1 1 1 0 1 1 1 1 0 1 1 1 0 0 0 1 1 0 0;
      1 1 0 0 1 0 0 1 0 1 1 0 0 0 0 0 0 1 1 1 0 1 1 1 1 0 1 1 1 0 0 0];
else
  chipLen = 16;
  % See Table 74 in IEEE 802.15.4,  2011 revision
  chipMap =  [0 0 1 1 1 1 1 0 0 0 1 0 0 1 0 1;
              0 1 0 0 1 1 1 1 1 0 0 0 1 0 0 1;
              0 1 0 1 0 0 1 1 1 1 1 0 0 0 1 0;
              1 0 0 1 0 1 0 0 1 1 1 1 1 0 0 0;
              0 0 1 0 0 1 0 1 0 0 1 1 1 1 1 0;
              1 0 0 0 1 0 0 1 0 1 0 0 1 1 1 1;
              1 1 1 0 0 0 1 0 0 1 0 1 0 0 1 1;
              1 1 1 1 1 0 0 0 1 0 0 1 0 1 0 0;
              0 1 1 0 1 0 1 1 0 1 1 1 0 0 0 0;
              0 0 0 1 1 0 1 0 1 1 0 1 1 1 0 0;
              0 0 0 0 0 1 1 0 1 0 1 1 0 1 1 1;
              1 1 0 0 0 0 0 1 1 0 1 0 1 1 0 1;
              0 1 1 1 0 0 0 0 0 1 1 0 1 0 1 1;
              1 1 0 1 1 1 0 0 0 0 0 1 1 0 1 0;
              1 0 1 1 0 1 1 1 0 0 0 0 0 1 1 0;
              1 0 1 0 1 1 0 1 1 1 0 0 0 0 0 1];
end


%% Synchronization header (SHR)

% Preamble is 4 octets, all set to 0.
preamble = zeros(4*8, 1);

% Start-of-frame delimiter (SFD)
SFD = [1 1 1 0 0 1 0 1]'; % value from standard (see Fig. 68, IEEE 802.15.4, 2011 Revision)

SHR = [preamble; SFD];

%% PHY Header (PHR)
frameLength = de2bi((length(message)/8)+2, 7); % Add 2 because we have HW CRC
PHR = [frameLength'; reservedValue];

%% PHY protocol data unit:
ppdu = [SHR; PHR; message];

% pre-allocate matrix for performance
chips = zeros(chipLen, length(ppdu)/4);
for idx = 1:length(ppdu)/4
  %% Bit to symbol mapping
  currBits = ppdu(1+(idx-1)*4 : idx*4);
  symbol = bi2de(currBits');
  
  %% Symbol to chip mapping                            
	chips(:, idx) = chipMap(1+symbol, :)'; % +1 for 1-based indexing
end



