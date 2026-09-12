#!/usr/bin/env python3
"""Drive only the isolated acceptance app; fail closed if the visible app changes."""
from pathlib import Path
import subprocess,xml.etree.ElementTree as E,re,time,sys
root=Path(__file__).resolve().parents[2]
p=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else root/'tmp/category-acceptance-new/ui'
if not p.is_relative_to(root/'tmp'):raise SystemExit('Evidence must stay inside project tmp')
p.mkdir(parents=True,exist_ok=True)
pkg='com.hulk.manualledger.acceptance'
def adb(*args):return subprocess.check_output(['adb',*args])
def dump():
 adb('shell','uiautomator','dump','/sdcard/category-acceptance.xml')
 data=adb('exec-out','cat','/sdcard/category-acceptance.xml')
 root=E.fromstring(data)
 if not any(n.get('package')==pkg for n in root.iter('node')):raise RuntimeError('Acceptance app not foreground; no click performed')
 return root,data
def click(label):
 root,_=dump(); nodes=[n for n in root.iter('node') if n.get('text')==label]
 if len(nodes)!=1:raise RuntimeError(f'Expected one {label} control, found {len(nodes)}')
 l,t,r,b=map(int,re.findall(r'\d+',nodes[0].get('bounds')))
 adb('shell','input','tap',str((l+r)//2),str((t+b)//2));time.sleep(.6)
def capture(name,label):
 root,data=dump();assert any(n.get('text')=='已选：'+label for n in root.iter('node')), 'Skin selection not displayed'
 (p/(name+'.xml')).write_bytes(data);(p/(name+'.png')).write_bytes(adb('exec-out','screencap','-p'));print(name,'verified',flush=True)
adb('shell','am','start','-n',pkg+'/com.hulk.manualledger.MainActivity');time.sleep(.7)
click('设置');click('经典绿');capture('verified-green','经典绿')
adb('shell','am','force-stop',pkg);adb('shell','am','start','-n',pkg+'/'+pkg+'.launcher.Green');time.sleep(.7)
click('设置');capture('verified-green-reopened','经典绿')
click('醒目红');capture('verified-red','醒目红')
adb('shell','am','force-stop',pkg);adb('shell','am','start','-n',pkg+'/'+pkg+'.launcher.Red');time.sleep(.7)
click('设置');capture('verified-red-reopened','醒目红')
