import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parent.parent
def module(name,path):
    spec=importlib.util.spec_from_file_location(name,ROOT/path)
    result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result);return result
repair=module('repair_egress','deploy/repair-egress.py')
importer=module('import_vpn','deploy/import-vpn.py')
NAMES=['server/main.mjs','server/gateway.mjs','server/egress.mjs','server/egress-probe.mjs','server/vpn-nodes.mjs','server/vpn-snapshot.mjs','deploy/install.sh','deploy/compose.yaml','deploy/Deploy.ps1','deploy/import-vpn.py','deploy/verify-egress.mjs']

@unittest.skipUnless(os.name=='nt' or os.getuid()==0,'Ownership-bound fixture requires UID0')
class RepairTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='familygram-egress-repair-test-');self.root=Path(self.temp.name)
        for folder in ['server','deploy','runtime']:(self.root/folder).mkdir()
        (self.root/'runtime/xray').write_bytes(b'runtime-fixture')
        self.payload={'updates':{},'unchanged':{'runtime/xray':repair.digest(self.root/'runtime/xray')}}
        for index,name in enumerate(NAMES):
            path=self.root/name;old=None
            if index<2:path.write_bytes(b'old-source');old=repair.digest(path)
            data=('new-'+name).encode()
            self.payload['updates'][name]={'old_sha256':old,'new_sha256':hashlib.sha256(data).hexdigest(),'content':base64.b64encode(data).decode()}
    def tearDown(self):self.temp.cleanup()
    def test_additions_and_updates_are_idempotent(self):
        repair.apply_updates(self.root,self.payload);repair.apply_updates(self.root,self.payload)
        for name,value in self.payload['updates'].items():self.assertEqual(repair.digest(self.root/name),value['new_sha256'])
    def test_conflicting_last_new_file_prevents_all_writes(self):
        (self.root/NAMES[-1]).write_bytes(b'unexpected-owner-file')
        with self.assertRaises(ValueError):repair.apply_updates(self.root,self.payload)
        self.assertEqual((self.root/NAMES[0]).read_bytes(),b'old-source')
        self.assertFalse((self.root/'server/egress.mjs').exists())
    def test_corrupted_runtime_prevents_source_updates(self):
        (self.root/'runtime/xray').write_bytes(b'changed')
        with self.assertRaises(ValueError):repair.apply_updates(self.root,self.payload)
        self.assertEqual((self.root/NAMES[0]).read_bytes(),b'old-source')

class ImportTests(unittest.TestCase):
    def test_saved_profiles_import_with_real_node_parser_and_no_provider_request(self):
        with tempfile.TemporaryDirectory(prefix='familygram-import-test-') as name:
            root=Path(name);export=root/'export.txt'
            export.write_text('vless://21b3c5d7-e9f1-4234-8567-89abcdef0123@vpn.example.com:443?security=tls#Region\n'+'vless://31b3c5d7-e9f1-4234-8567-89abcdef0123@vpn.example.com:443?security=tls#%D0%91%D0%A1',encoding='utf-8')
            def command(args,timeout=15,data=None):
                if args[1]=='ps':return subprocess.CompletedProcess(args,0,'a'*12+'\n','')
                if args[1]=='inspect':return subprocess.CompletedProcess(args,0,'24projects|vpn|true\n','')
                self.assertEqual(args[:4],['docker','exec','-i','a'*12])
                self.assertNotIn('-u',args)
                return subprocess.run(['node','--input-type=module','-'],input=data,text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout,env={**os.environ,'VPN_STATE_DIR':str(root),'VPN_SUBSCRIPTION_FILE':str(export)})
            with patch.object(importer,'command',side_effect=command),patch.object(importer,'private_write') as write:
                importer.import_profiles(ROOT,root)
            snapshot=write.call_args.args[1]
            self.assertEqual([c['tier'] for c in snapshot['candidates']],['regular','bs'])
            self.assertTrue(all(c['outbound']['protocol']=='vless' for c in snapshot['candidates']))

if __name__=='__main__':unittest.main()
