"""Give the optional libraries' D8 record marker a private identity without altering DEX layout."""
import hashlib,struct,zlib,zipfile
from pathlib import Path
from verify_dex import classes
MARKER=b'Lcom/android/tools/r8/RecordTag;'

def integer(data,at):
    value=shift=0
    while True:
        byte=data[at];at+=1;value|=(byte&127)<<shift
        if byte<128:return value,at
        shift+=7
        if shift>35:raise ValueError('Invalid DEX integer')

def marker_record(data):
    names=list(classes(data))
    if MARKER not in names:return None
    index=names.index(MARKER);count,offset=struct.unpack_from('<II',data,96);record=struct.unpack_from('<8I',data,offset+index*32)
    def string(i):
        at=struct.unpack_from('<I',data,struct.unpack_from('<I',data,60)[0]+4*i)[0];_,at=integer(data,at);return data[at:data.index(0,at)]
    def typ(i):return string(struct.unpack_from('<I',data,struct.unpack_from('<I',data,68)[0]+4*i)[0])
    if record[1]!=0x1401 or typ(record[2])!=b'Ljava/lang/Object;' or record[3] or record[5] or record[7]:raise ValueError('RecordTag metadata differs')
    at=record[6];numbers=[]
    for _ in range(4):value,at=integer(data,at);numbers.append(value)
    if numbers!=[0,0,1,0]:raise ValueError('RecordTag must have only one direct constructor')
    method,at=integer(data,at);flags,at=integer(data,at);code,at=integer(data,at)
    # New D8 makes this constructor protected. The pinned original's public
    # constructor has wider access and the exact same Object.<init> behavior.
    if flags not in (0x11001,0x11004):raise ValueError('RecordTag constructor flags differ')
    registers,inputs,outputs,tries,debug,length=struct.unpack_from('<HHHHII',data,code)
    if (registers,inputs,outputs,tries,length)!=(1,1,1,0,4):raise ValueError('RecordTag constructor shape differs')
    instructions=struct.unpack_from('<4H',data,code+16)
    if (instructions[0],instructions[2],instructions[3])!=(0x1070,0,0x000e):raise ValueError('RecordTag constructor instructions differ')
    owner,proto,name=struct.unpack_from('<HHI',data,struct.unpack_from('<I',data,92)[0]+instructions[1]*8)
    shorty,returned,parameters=struct.unpack_from('<III',data,struct.unpack_from('<I',data,76)[0]+proto*12)
    if typ(owner)!=b'Ljava/lang/Object;' or string(name)!=b'<init>' or string(shorty)!=b'V' or parameters:raise ValueError('RecordTag constructor target differs')
    return index,count,offset

def share_marker(dex_path,baseline_apk):
    with zipfile.ZipFile(baseline_apk) as archive:
        found=False
        for name in archive.namelist():
            if name=='classes.dex' or (name.startswith('classes') and name.endswith('.dex') and name[7:-4].isdigit() and int(name[7:-4])<9):
                found=marker_record(archive.read(name)) is not None or found
    if not found:raise ValueError('Pinned original RecordTag missing')
    data=bytearray(Path(dex_path).read_bytes());marker_record(data)
    replacement=b'Lcom/android/tools/r8/RecordSaa;'
    if len(replacement)!=len(MARKER):raise ValueError('DEX layout must remain unchanged')
    count,offset=struct.unpack_from('<II',data,56);strings=[];positions=[]
    for n in range(count):
        at=struct.unpack_from('<I',data,offset+4*n)[0];_,at=integer(data,at);end=data.index(0,at);positions.append((at,end));strings.append(bytes(data[at:end]))
    if MARKER not in strings:return False
    index=strings.index(MARKER)
    if not (index==0 or strings[index-1]<replacement) or not (index+1==len(strings) or replacement<strings[index+1]):raise ValueError('Relocated marker would violate DEX string ordering')
    start,end=positions[index];data[start:end]=replacement
    data[12:32]=hashlib.sha1(data[32:]).digest();struct.pack_into('<I',data,8,zlib.adler32(data[12:])&0xffffffff)
    if MARKER in set(classes(bytes(data))):raise ValueError('Private marker relocation failed')
    Path(dex_path).write_bytes(data);return True
