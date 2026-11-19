"""What went through the NAT, by the AWS service of the far address (D3, D4).

Run by nat-traffic.sh inside a throwaway python container; reads only its mounted inputs:
  /logs      the day's VPC flow log objects (Parquet, default v2 fields)
  /in        subnets.txt (CIDR and Name per subnet), s3.cidrs and dynamodb.cidrs (the gateway
             endpoints' prefix lists), ip-ranges.json (AWS's published ranges)

The NAT's own interface is the one with an address in a public subnet. Every byte it moved
to or from outside the VPC is attributed to S3, DynamoDB, or the AWS service whose published
range holds the address. S3 or DynamoDB through the NAT means a gateway endpoint was missed;
CLOUDFRONT means a public image (public.ecr.aws) was pulled past the pull-through cache.
"""
import collections
import glob
import ipaddress
import json
import os


def networks(path):
    return [ipaddress.ip_network(line.strip()) for line in open(path) if line.strip()]


s3 = networks("/in/s3.cidrs")
dynamodb = networks("/in/dynamodb.cidrs")
subnets = [(ipaddress.ip_network(line.split()[0]), line.split()[1]) for line in open("/in/subnets.txt")]
public = [n for n, name in subnets if "public" in name]
ranges = [(ipaddress.ip_network(p["ip_prefix"]), p["service"])
          for p in json.load(open("/in/ip-ranges.json"))["prefixes"]]


def far_end(address):
    a = ipaddress.ip_address(address)
    if any(a in n for n in s3):
        return "S3 (gateway endpoint missed)"
    if any(a in n for n in dynamodb):
        return "DYNAMODB (gateway endpoint missed)"
    services = sorted({s for n, s in ranges if a in n} - {"AMAZON"})
    return ",".join(services) or "AMAZON / not AWS"


import pyarrow.parquet as pq  # noqa: E402  (installed at container start)

# Optional: only flows that started at or after this epoch second (one session of a day).
SINCE = int(os.environ.get("SINCE", "0"))
rows = []
files = glob.glob("/logs/**/*.parquet", recursive=True)
for f in files:
    table = pq.read_table(f, columns=["interface_id", "srcaddr", "dstaddr", "bytes", "start"]).to_pylist()
    rows += [r for r in table if r["srcaddr"] not in (None, "-") and (r["start"] or 0) >= SINCE]

nat = {r["interface_id"] for r in rows
       if any(ipaddress.ip_address(r["srcaddr"]) in n for n in public)}
print(f"{len(rows)} records in {len(files)} files; NAT interfaces: {len(nat)}")

inbound = collections.Counter()
outbound = collections.Counter()
for r in rows:
    if r["interface_id"] not in nat:
        continue
    src, dst = ipaddress.ip_address(r["srcaddr"]), ipaddress.ip_address(r["dstaddr"])
    if not src.is_private:
        inbound[far_end(r["srcaddr"])] += r["bytes"] or 0
    elif not dst.is_private:
        outbound[far_end(r["dstaddr"])] += r["bytes"] or 0

# The other side of the claim: traffic with S3 or DynamoDB addresses on the tasks' own
# interfaces, which only the gateway endpoints can carry there without the NAT.
endpoint = collections.Counter()
for r in rows:
    if r["interface_id"] in nat:
        continue
    for address, direction in ((r["srcaddr"], "from"), (r["dstaddr"], "to")):
        a = ipaddress.ip_address(address)
        if any(a in n for n in s3):
            endpoint[f"{direction} S3"] += r["bytes"] or 0
        elif any(a in n for n in dynamodb):
            endpoint[f"{direction} DynamoDB"] += r["bytes"] or 0

print("\nthrough the gateway endpoints (task, ALB and VPC link interfaces), not the NAT:")
for k in ("from S3", "to S3", "from DynamoDB", "to DynamoDB"):
    print(f"  {endpoint[k] / 1e6:10.2f} MB  {k}")

for title, counter in (("into the VPC through the NAT", inbound), ("out of the VPC through the NAT", outbound)):
    print(f"\n{title}, by the far end's AWS service:")
    for service, b in counter.most_common():
        print(f"  {b / 1e6:10.2f} MB  {service}")
