test1_payload = dec2bin(194151,128)-'0';
[ppdu, chips] = GenerateLRWPANPacket(test1_payload');

byte_payload = [];
for i=8:8:length(test1_payload)
    byte = bin2dec(reverse(num2str(test1_payload(i-7:i))));
    byte_payload = [byte_payload byte];
end


fid = fopen('packetassembler-test.txt','w');
print_sequence(fid, "input payload", byte_payload);
print_sequence(fid, "output ppdu", ppdu);
chips = chips(:);
print_sequence(fid, "output chips", chips);
fclose(fid);


function print_sequence(fid, seq_name, seq)
    fprintf(fid,'%s\n', seq_name);
    fprintf(fid,'Seq(');
    for i=1:length(seq)-1
        fprintf(fid,'%d,',seq(i));
    end
    fprintf(fid,'%d)\n', seq(length(seq)));
end