bt = 0.5;
span = 2; % 2 symbols
sps = 16; % 16 samples per symbol
h = gaussdesign(bt,span,sps);
fvtool(h,'impulse')

tapstr = [];
for i=1:length(h)
    tapstr = [tapstr num2str(roundn(h(i),-6),'%.6f') ', '];
end
disp(tapstr)