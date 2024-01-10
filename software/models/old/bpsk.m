freq = 1e6;
sps = freq * 20;
period = 1/freq;
t = linspace(0,period,1+sps/freq);
t = t(1:end-1);
zero = -sin(2.*pi.*freq.*t);
one  = +sin(2.*pi.*freq.*t);

code = 1000000;
codeBin = dec2bin(code,32);

waveform = [];
for i = 1:length(codeBin)
    if codeBin(i) == '1'
        waveform = [waveform, one];
    else
        waveform = [waveform, zero];
    end
end

tfull = linspace(0,period*length(codeBin),length(waveform));
lOscWave = sin(2.*pi.*freq.*tfull + 0.*pi/4);

detect = lOscWave.*waveform;

[b,a]=butter(5,2*freq/sps);
detectf = filter(b,a,detect);

figure(1);
plot(tfull, waveform, tfull, detect, tfull, detectf);

figure(2);
pwelch(waveform, 200);
pause;
pwelch(detect, 200);
pause;
pwelch(detectf, 200);
