#!/usr/bin/env python3
"""Unit tests for research tool on synthetic tensors, no model download."""
import argparse
import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np
from safetensors.numpy import load_file, save_file
sys.path.insert(0, str(Path(__file__).parent))
import weight_space_edit as edit


class WeightSpaceEditTest(unittest.TestCase):
    def test_contrast_direction_and_sha_guard(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            normal=root/"normal.npy"; bad=root/"repeating.npy"; direction=root/"direction.npy"
            np.save(normal,np.array([[1.,0.,0.],[0.9,0.,0.],[1.1,0.,0.]],dtype="float32"))
            np.save(bad,np.array([[1.,2.,0.],[1.,2.1,0.],[1.,1.9,0.]],dtype="float32"))
            with contextlib.redirect_stdout(io.StringIO()):
                edit.make_direction(argparse.Namespace(normal=str(normal),
                    repeating=str(bad),output=str(direction)))
            d=np.load(direction)
            self.assertEqual((3,),d.shape)
            self.assertTrue(abs(d[1])>0.99)
            src=root/"original.safetensors"
            W=np.ones((3,6),dtype="float32")
            save_file({"model.layers.0.mlp.down_proj.weight":W,
                       "unmodified":np.zeros((500,20),dtype="float32")},str(src))
            sha=edit.sha256(src); output=root/"patched.safetensors"
            request=dict(checkpoint=str(src),output=str(output),
                         tensor="model.layers.0.mlp.down_proj.weight",
                         columns="0,1",direction=str(direction),
                         expected_sha256=sha,strength=0.05,
                         max_modified_fraction=0.001,
                         max_relative_delta=0.05,write=False)
            with contextlib.redirect_stdout(io.StringIO()):
                edit.patch(argparse.Namespace(**request))
            self.assertFalse(output.exists(),"Default mode must NEVER write anything.")
            request["write"]=True
            with contextlib.redirect_stdout(io.StringIO()):
                edit.patch(argparse.Namespace(**request))
            before=load_file(str(src)); after=load_file(str(output))
            self.assertEqual(sha,edit.sha256(src),"Original must be unmodified.")
            self.assertFalse(np.array_equal(before[request["tensor"]],after[request["tensor"]]))
            self.assertTrue(np.array_equal(before["unmodified"],after["unmodified"]))
            report=json.loads(output.with_suffix(".patch.json").read_text())
            self.assertFalse(report["evaluation_passed"])
            self.assertFalse(report["activated_in_CHK"])
            with self.assertRaises(ValueError):
                edit.patch(argparse.Namespace(**{**request,
                    "output":str(root/"wrong.safetensors"),
                    "expected_sha256":"0"*64}))


if __name__ == "__main__":
    unittest.main()
