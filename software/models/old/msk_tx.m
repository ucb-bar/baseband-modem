close all;
clear all;

% generate a 32 bit binary sig
code = 44;
codeBin = dec2bin(code,32);
codeBin = double(codeBin-'0');

fb = 2e6; % baseband frequency = 2 MHz
OF = 50 % oversampling factor
bb_sps = fb * OF; % Oversampling
bb_period = 1/fb;

fc = 24e8; % carrier frequency = 2.4 GHz

I_bits = codeBin(1:2:end);
I_bits = 2*I_bits - 1; % NRZ encoding
Q_bits = codeBin(2:2:end);
Q_bits = 2*Q_bits - 1; % NRZ encoding

tmpI = [];
tmpQ = [];
for i=1:length(I_bits)
   sym = repmat(I_bits(i),1,OF);
   tmpI = [tmpI sym];
   sym = repmat(Q_bits(i),1,OF);
   tmpQ = [tmpQ sym];
end

I_bits = tmpI;
Q_bits = tmpQ;
I_bits = [I_bits ones(1,2*OF)];
Q_bits = [ones(1,2*OF) Q_bits];


% Plot the NRZ-encoded bits
figure;
t = 1:length(I_bits);
plot(t,I_bits,t,Q_bits);
axis([-1, max(t), -2, 2]);
title('Split & NRZ-Encoded Bits');
legend('I bits','Q bits');


% Generate a raised cosine filter 
% Believe this to be equivalent to a half-sine pulse shaping filter when
% span is 1 symbol
rf = 0.50; %rolloff 
span = 1; % filter span

psf = rcosdesign(rf, span, OF);
fvtool(psf, 'Analysis','impulse'); % Plot the impulse response
I_shaped = []; 
Q_shaped = [];

% Apply the pulse shaping filter as a FIR filter
% Where the window equals the oversampling factor
bb_t = 1/bb_sps;
for i=1:OF:length(I_bits)
    I_seg = I_bits(i:i+(OF)-1);
    Q_seg = Q_bits(i:i+(OF)-1);
    f_seg = upfirdn(I_seg, psf);
    I_shaped = [I_shaped f_seg];
    f_seg = upfirdn(Q_seg, psf);
    Q_shaped = [Q_shaped f_seg];
end

figure;
plot(I_shaped);
title("Pulse shaped I signal")
legend("I signal");

% Modulate onto baseband (should be carrier but w/e)
t = 0:1/bb_sps:(length(I_shaped)-1)/bb_sps;
I_sig = I_shaped .* cos(2*pi*fb*t);
Q_sig = Q_shaped .* sin(2*pi*fb*t);
figure;
plot(t, I_sig, t, Q_sig);
legend('I', 'Q');
title('O-QPSK Modulated I&Q signals');

% Sum sigs & plot
sum_sig = I_shaped + Q_shaped;
figure;
plot(t, sum_sig);
title('Summed I&Q signal');


% Simulate the RX chain a bit
% Should add some broadband noise
% And some phase shift for decoherence
[b,a]=butter(5,2*fb/bb_sps); % LPF
detectf = filter(b,a,sum_sig);

figure;
plot(t, sum_sig, t, detectf);
title("Raw Signal vs Filtered Signal at Receiver");
legend('Raw signal', 'Filtered signal');

% Plot PSD of the summed sig
figure;
pwelch(sum_sig, 2*OF);
