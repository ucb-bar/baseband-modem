
% Load the Simulink model workspace and get handles to all
% the model parameters
hws = get_param(bdroot, 'modelworkspace');

fc = 2e3; % Set the free-running frequency [Hz] of the VCO 
fd = 0.5e3; % Set the VCO sensitivity [Hz/V] 
lpf_cutoff = 8e3; % Low-pass filter cutoff frequency

% 
dac_sample_rate = 32e3;
dac_sample_time = 1/dac_sample_rate;
hws.assignin("vco_sens", fd)
hws.assignin("vco_f0", fc)

[num, denom] = besself(16, 2*pi*lpf_cutoff,'low');
hws.assignin('filter_num',num)
hws.assignin('filter_denom',denom)
hws.assignin('dac_sample_time', dac_sample_time)

% Import the MSK modulator output codes
A = readmatrix("../../../../msktx_vco_out.csv");
A = rmmissing(A); % Remove missing values
A = (A - 31) / 32; % Normalize to the output resolution

% Import the MSK modulator input bits
B = readmatrix("../../../../msktx_bits_in.csv");
B = rmmissing(B);

B_ext = [];
for i=1:length(B)
    B_ext = [B_ext repmat(B(i), 1, 16)];
end
B_ext = [zeros(1,32), B_ext];
B = B_ext;

% Create the time series based on the sample rate of the circuit
time = dac_sample_time*(0:(length(A)-1));
A = A';
time = time';
vco_mod_data = timeseries(A, time);
save("vco_mod_data.mat", "vco_mod_data", "-v7.3");

B = B';
bit_in_data = timeseries(B, time);
save("bit_in_data.mat", "bit_in_data", "-v7.3");


sim("vco.slx")