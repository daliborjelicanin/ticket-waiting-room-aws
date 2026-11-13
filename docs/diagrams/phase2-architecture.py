"""Phase 2 target topology with official AWS icons — the picture for docs/phase2-plan.md §1.

A *target*, not the built state: docs/architecture.md §1 (and architecture.png) stay the
picture of what runs until Phase 2 builds this. The Mermaid in phase2-plan.md §1 is the
source of truth. Regenerate after any change there:

    docker run --rm -v "$PWD:/out" gtramontina/diagrams:0.23.4 docs/diagrams/phase2-architecture.py
"""
from diagrams import Cluster, Diagram, Edge
from diagrams.aws.compute import ECR, ECS, Lambda
from diagrams.aws.database import Dynamodb, ElastiCache, RDS
from diagrams.aws.engagement import SES
from diagrams.aws.integration import Eventbridge, SNS, SQS, StepFunctions
from diagrams.aws.management import Cloudtrail, Cloudwatch
from diagrams.aws.network import ALB, APIGateway, Endpoint, InternetGateway, NATGateway
from diagrams.aws.security import KMS, Cognito, Guardduty, SecretsManager, WAF
from diagrams.aws.storage import S3
from diagrams.onprem.client import Users

GRAPH = {
    "fontsize": "22",
    "fontname": "Sans-Serif",
    "labelloc": "t",
    "pad": "0.6",
    "nodesep": "0.45",
    "ranksep": "1.3",
    "splines": "spline",
}
NODE = {"fontsize": "13", "fontname": "Sans-Serif"}
EDGE = {"fontsize": "11", "fontname": "Sans-Serif"}
SESSION = {"bgcolor": "#FFF4E5", "pencolor": "#E8A33D", "style": "dashed,rounded"}

with Diagram(
    "ticket-waiting-room — Phase 2 target (Terraform, eu-central-1)\n"
    "dashed orange = session stack (by the hour), everything else = foundation",
    filename="docs/diagrams/phase2-architecture",
    outformat="png",
    show=False,
    direction="LR",
    graph_attr=GRAPH,
    node_attr=NODE,
    edge_attr=EDGE,
):
    buyer = Users("buyers (browser)\n+ gate scanners")
    cognito = Cognito("Cognito user pool\nM2M + gate scanner\n(ticketing/gate)")

    with Cluster("Regional edge"):
        with Cluster("session", graph_attr=SESSION):
            waf = WAF("WAF on queue join + purchase\nIP · JA4 · token · Bot Control\nanonymous IP · baseline on all")
        apigw = APIGateway("API Gateway REST\nREGIONAL\nstage 'dev' (session)")
        wsapi = APIGateway("API Gateway WebSocket\n$connect consumes a\nsingle-use ticket (D18)")

    with Cluster("Own VPC — 3 AZs, three subnet tiers, per-service SGs"):
        igw = InternetGateway("IGW")
        with Cluster("public subnets"):
            with Cluster("session", graph_attr=SESSION):
                nat = NATGateway("zonal NAT Gateway\nAZ a")
        with Cluster("private app subnets"):
            with Cluster("session", graph_attr=SESSION):
                alb = ALB("internal ALB\nvia VPC Link v2\nno X-Edge-Secret")
                with Cluster("ECS Fargate ARM64 — Spot in dev, 2 tasks/AZ for the proof"):
                    waiting = ECS("waiting-room")
                    seats = ECS("seat-inventory")
                    checkout = ECS("checkout-payment")
                    issuance = ECS("ticket-issuance\n+ atomic gate check-in")
                    notify = ECS("notification")
                    migrate = ECS("migration task\n(one-off per deploy)")
        with Cluster("isolated db subnets — no route out"):
            with Cluster("session", graph_attr=SESSION):
                valkey = ElastiCache("Valkey t4g.micro\nTLS in transit")
                rds = RDS("Postgres t4g.micro\nprivate, CMK")
                secret = SecretsManager("RDS managed\nsecret")
        endpoints = Endpoint("gateway endpoints\nS3 + DynamoDB\n(free)")

    with Cluster("Regional data and messaging"):
        ddb = Dynamodb("DynamoDB\nseat-inventory\n+ connections")
        s3 = S3("S3 QR bucket")
        queues = SQS("SQS queues + DLQs")
        topic = SNS("purchase-confirmed")
        kms = KMS("KMS CMK")

    with Cluster("Orchestration and functions"):
        with Cluster("session", graph_attr=SESSION):
            saga = StepFunctions("checkout-saga")
            scheduler = Eventbridge("hold cleanup\nschedule")
        lambdas = Lambda("3 Lambdas ARM64\nhold-cleanup · QR\n· admission notifier")

    ecr = ECR("ECR\nimages by digest")
    ops = Cloudwatch("CloudWatch\nalarms → email")
    with Cluster("Account baseline"):
        trail = Cloudtrail("CloudTrail\n+ security alerts")
        guard = Guardduty("GuardDuty")
    ses = SES("SES")

    buyer >> Edge(label="sign in") >> cognito
    buyer >> Edge(label="Bearer token") >> waf >> apigw
    buyer >> Edge(label="wss ?ticket=") >> wsapi >> Edge(label="delete ticket +\nput connection") >> ddb
    waiting >> Edge(label="issue ticket\n(conditional cap)", style="dashed") >> endpoints
    apigw >> Edge(label="private integration") >> alb
    alb >> [waiting, seats, checkout, issuance]
    apigw >> Edge(label="StartExecution ↔ http:invoke", dir="both") >> saga

    waiting >> valkey
    seats >> Edge(label="conditional writes") >> endpoints >> ddb
    issuance >> Edge(label="presign / QR") >> endpoints >> s3
    [checkout, issuance, migrate] >> rds
    rds - Edge(style="dotted") - secret
    kms >> Edge(style="dotted", label="encrypts") >> [rds, s3, queues]

    saga >> [queues, topic]
    queues >> [issuance, notify, lambdas]
    topic >> queues
    notify >> ses
    scheduler >> lambdas

    [waiting, notify] >> Edge(label="via NAT: SQS/SNS/Logs/JWKS", style="dashed", color="#E8A33D") >> nat >> igw
    ecr >> Edge(label="layers via S3 endpoint", style="dashed") >> seats
    [queues, saga, alb] >> Edge(style="dotted") >> ops
    [trail, guard] >> Edge(style="dotted") >> ops
