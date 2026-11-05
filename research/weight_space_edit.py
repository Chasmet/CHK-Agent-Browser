#!/usr/bin/env python3
"""Research-only weight-space patcher, not a training script or Android runtime.

Commands:
  inspect --checkpoint model.safetensors
  direction --normal normal.npy --repeating repeating.npy --output direction.npy
  patch --checkpoint model.safetensors --output trial.safetensors --tensor ...
        --direction direction.npy --columns 2,3 --expected-sha256 SHA [--write]

Requires numpy and safetensors. The original GGUF used by CHK is NEVER touched.
A tested float checkpoint would need independent GGUF conversion and quantization,
followed by separate held-out evaluations before it can replace a model in CHK.
"""
import argparse
import hashlib
import json
from pathlib import Path


def sha256(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def require_source(path):
    path = Path(path)
    if not path.is_file() or path.suffix != ".safetensors":
        raise ValueError("Input must be an existing .safetensors file, not a GGUF.")
    return path


def inspect(args):
    from safetensors import safe_open
    path = require_source(args.checkpoint)
    with safe_open(str(path), framework="np", device="cpu") as handle:
        tensor_names = list(handle.keys())
        print(json.dumps({
            "checkpoint": str(path), "sha256": sha256(path),
            "tensor_count": len(tensor_names),
            "tensors": [{"name": name,
                         "shape": list(handle.get_slice(name).get_shape())}
                        for name in tensor_names
                        if args.contains.lower() in name.lower()][:args.max_tensors]
        }, ensure_ascii=False, indent=2))


def make_direction(args):
    import numpy as np
    good = np.load(args.normal, allow_pickle=False)
    bad = np.load(args.repeating, allow_pickle=False)
    if good.ndim != 2 or bad.ndim != 2 or good.shape[1] != bad.shape[1]:
        raise ValueError("Activation samples must have shapes (N, hidden_size).")
    if good.shape[0] < 3 or bad.shape[0] < 3:
        raise ValueError("At least three independently gathered examples per class required.")
    direction = bad.astype("float64").mean(axis=0) - good.astype("float64").mean(axis=0)
    norm = float(np.linalg.norm(direction))
    if not np.isfinite(norm) or norm < 1e-10:
        raise ValueError("No measurable contrast direction.")
    output = Path(args.output)
    if output.exists():
        raise FileExistsError("Do not overwrite a previous direction.")
    np.save(output, (direction / norm).astype("float32"), allow_pickle=False)
    print(json.dumps({"output": str(output), "dimensions": direction.size,
                      "source": "activation_mean_difference_no_gradients",
                      "validated_improvement": False}))


def patch(args):
    import numpy as np
    from safetensors.numpy import load_file, save_file
    path = require_source(args.checkpoint)
    output = Path(args.output)
    if output.resolve() == path.resolve() or output.exists():
        raise ValueError("Output must be a fresh checkpoint; never overwrite the original.")
    actual_hash = sha256(path)
    if actual_hash.lower() != args.expected_sha256.lower():
        raise ValueError("Input SHA256 mismatch. Refusing to patch an unknown checkpoint.")
    # The experiment must target a specific matrix, not the entire model.
    if ".mlp.down_proj.weight" not in args.tensor:
        raise ValueError("Only a named MLP down-projection may be edited by this prototype.")
    tensors = load_file(str(path))
    if args.tensor not in tensors:
        raise KeyError("Selected tensor not present in this checkpoint.")
    W = tensors[args.tensor]
    if W.ndim != 2 or W.dtype.kind != "f":
        raise ValueError("Expected a two-dimensional floating-point tensor.")
    columns = sorted(set(int(x) for x in args.columns.split(",") if x.strip()))
    if not columns or len(columns) > 64 or min(columns) < 0 or max(columns) >= W.shape[1]:
        raise ValueError("Column indices out of range or too many columns.")
    direction = np.load(args.direction, allow_pickle=False)
    if direction.ndim != 1 or direction.shape[0] != W.shape[0]:
        raise ValueError("Direction dimension does not match down-projection output width.")
    if not np.all(np.isfinite(direction)):
        raise ValueError("Direction contains nonfinite values.")
    u = direction.astype("float64")
    magnitude = np.linalg.norm(u)
    if magnitude <= 1e-10:
        raise ValueError("Direction has zero norm.")
    u /= magnitude
    if not 0 < args.strength <= 1:
        raise ValueError("Strength must be in (0, 1].")
    # Count ALL tensors in this shard to bound the scale of the change.
    all_params = sum(int(np.prod(t.shape)) for t in tensors.values())
    modified = len(columns) * W.shape[0]
    fraction = modified / max(1, all_params)
    if fraction > args.max_modified_fraction:
        raise ValueError(f"Requested patch affects {fraction:.5%}, above safety budget.")
    edited = np.array(W, copy=True)
    before = edited[:, columns].astype("float64")
    projections = u @ before
    after = before - args.strength * np.outer(u, projections)
    delta = float(np.linalg.norm(after - before) / max(np.linalg.norm(before), 1e-12))
    if delta > args.max_relative_delta:
        raise ValueError(f"Relative update {delta:.6f} exceeds allowed bound.")
    edited[:, columns] = after.astype(edited.dtype)
    if not np.all(np.isfinite(edited)):
        raise ValueError("Patch produced NaNs or infinities.")
    changed = int(np.count_nonzero(edited != W))
    report = {
        "method": "gradient_free_steering_direction_projection",
        "source_sha256": actual_hash, "tensor": args.tensor,
        "columns": columns, "fraction_of_shard_parameters": fraction,
        "relative_delta_selected_columns": delta, "changed_values": changed,
        "training_steps": 0, "gradients_used": False,
        "evaluation_passed": False, "activated_in_CHK": False,
        "mode": "write" if args.write else "dry_run"
    }
    if args.write:
        tensors[args.tensor] = edited
        output.parent.mkdir(parents=True, exist_ok=True)
        # Save the edited shard separately, leaving the source unchanged.
        save_file(tensors, str(output))
        report["output"] = str(output)
        report["output_sha256"] = sha256(output)
        report["source_preserved"] = sha256(path) == actual_hash
        with output.with_suffix(".patch.json").open("w", encoding="utf-8") as out:
            json.dump(report, out, indent=2, ensure_ascii=False)
    print(json.dumps(report, indent=2))


def main():
    p = argparse.ArgumentParser(description="Isolated weight-space programming experiment.")
    sub = p.add_subparsers(dest="action", required=True)
    a = sub.add_parser("inspect")
    a.add_argument("--checkpoint", required=True)
    a.add_argument("--contains", default="")
    a.add_argument("--max-tensors", type=int, default=50)
    a.set_defaults(func=inspect)
    d = sub.add_parser("direction")
    d.add_argument("--normal", required=True)
    d.add_argument("--repeating", required=True)
    d.add_argument("--output", required=True)
    d.set_defaults(func=make_direction)
    q = sub.add_parser("patch")
    q.add_argument("--checkpoint", required=True)
    q.add_argument("--output", required=True)
    q.add_argument("--tensor", required=True)
    q.add_argument("--direction", required=True)
    q.add_argument("--columns", required=True)
    q.add_argument("--expected-sha256", required=True)
    q.add_argument("--strength", type=float, default=0.3)
    q.add_argument("--max-modified-fraction", type=float, default=0.001)
    q.add_argument("--max-relative-delta", type=float, default=0.05)
    q.add_argument("--write", action="store_true")
    q.set_defaults(func=patch)
    a = p.parse_args()
    a.func(a)


if __name__ == "__main__":
    main()
