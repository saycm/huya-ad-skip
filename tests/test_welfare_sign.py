from pathlib import Path
import subprocess
import tempfile
import unittest

from test_huya_loader_lifecycle import ROOT, JDK, EXE

HARNESS = r"""
package io.github.saycm.huya.skipad;
public class WelfareSignTest {
 public static void main(String[] args) {
  final int[] calls={0}, finished={0};
  final String[] state={""};
  WelfareSignRunner.Host host=new WelfareSignRunner.Host() {
   public boolean active(){return !args[0].equals("off");}
   public boolean claim(){return !args[0].equals("duplicate");}
   public void log(String s,String d){state[0]=s;}
   public void send(TaskWire.Struct req,NativeTaskRunner.Reply cb){
    calls[0]++;
    if(req.number(1)!=2 || !req.text(2).equals("frontpage")) throw new AssertionError("request");
    TaskWire.Struct body=new TaskWire.Struct();
    if(args[0].equals("success"))body.field(5,new TaskWire.Struct().field(1,2));
    if(args[0].equals("security"))body.field(1,3).field(5,new TaskWire.Struct().field(1,2));
    cb.complete(body,0); cb.complete(body,0);
   }
  };
  new WelfareSignRunner(host,()->finished[0]++).start();
  if(finished[0]!=1)throw new AssertionError("finish");
  if(calls[0]!=(args[0].equals("off")||args[0].equals("duplicate")?0:1))throw new AssertionError("calls");
  if(state[0].equals("成功")!=args[0].equals("success"))throw new AssertionError("receipt");
 }
}
"""

class WelfareSignTests(unittest.TestCase):
    def test_sign_requires_confirmed_receipt_and_runs_once(self):
        source = ROOT / "tools/huya-compat-src/io/github/saycm/huya/skipad"
        with tempfile.TemporaryDirectory(dir=ROOT / "build/test-tmp") as directory:
            folder = Path(directory)
            harness = folder / "WelfareSignTest.java"
            harness.write_text(HARNESS, encoding="utf-8")
            subprocess.run([str(JDK / "bin" / ("javac" + EXE)), "-encoding", "UTF-8",
                            "-d", str(folder), *[str(source / (n + ".java")) for n in
                            ("TaskWire", "GlassSettings", "NativeTaskCatalog", "NativeTaskRunner",
                             "WelfareSignRunner")], str(harness)], check=True, capture_output=True)
            for case in ("success", "security", "missing", "off", "duplicate"):
                result = subprocess.run([str(JDK / "bin" / ("java" + EXE)), "-cp", str(folder),
                                         "io.github.saycm.huya.skipad.WelfareSignTest", case],
                                        capture_output=True)
                self.assertEqual(result.returncode, 0, result.stderr.decode("utf-8", errors="replace"))
