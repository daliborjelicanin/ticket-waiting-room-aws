"""The system topology with official AWS icons — the README's opening picture.

Mirrors docs/architecture.md §1 (the Mermaid diagram stays the source of truth: it renders
on GitHub and in IntelliJ and diffs as text). This one exists because a reader's first
look at an AWS architecture expects the icons, and Mermaid cannot draw them where GitHub
renders it. Regenerate after any change to §1:

    docker run --rm -v "$PWD:/out" gtramontina/diagrams:0.23.4 docs/diagrams/architecture.py

which writes docs/diagrams/architecture.png. Nothing to install — the image carries
Graphviz and the diagrams library.
"""
from diagrams import Cluster, Diagram, Edge
from diagrams.aws.compute import ECS, Lambda
from diagrams.aws.database import Dynamodb, ElastiCache, RDS
from diagrams.aws.engagement import SES
from diagrams.aws.integration import Eventbridge, SNS, SQS, StepFunctions
from diagrams.aws.network import ALB, APIGateway
from diagrams.aws.security import Cognito, WAF
from diagrams.aws.storage import S3
from diagrams.onprem.client import Users

GRAPH = {
    "fontsize": "22",
    "fontname": "Sans-Serif",
    "labelloc": "t",
    "pad": "0.6",
    "nodesep": "0.45",
    "ranksep": "1.4",
    "splines": "spline",
    "concentrate": "false",
}
NODE = {"fontsize": "13", "fontname": "Sans-Serif"}
EDGE = {"fontsize": "11", "fontname": "Sans-Serif"}

with Diagram(
    "ticket-waiting-room — Phase 1 on ECS Fargate (eu-central-1)",
    filename="docs/diagrams/architecture",
    outformat="png",
    show=False,
    direction="LR",
    graph_attr=GRAPH,
    node_attr=NODE,
    edge_attr=EDGE,
):
    buyer = Users("buyers\n(browser)")
    cognito = Cognito("Cognito user pool\naccess + ID tokens\nM2M client for the saga")

    with Cluster("Edge — regional services"):
        waf = WAF("WAF\nrate limit + Bot Control\non POST /purchase")
        apigw = APIGateway("API Gateway REST\n/{proxy+} → ALB\nPOST /purchase → saga")
        wsapi = APIGateway("API Gateway WebSocket\n$connect/$disconnect →\nDynamoDB, no Lambda")

    saga = StepFunctions("checkout-saga\nhold → order → charge →\nconfirm → issue → notify")

    # Phase 1's deliberate network simplification, drawn rather than hidden: the default VPC,
    # public subnets, no NAT Gateway (tasks carry public IPs to reach ECR and the regional
    # services), everything pinned to eu-central-1a, and security-group-to-security-group
    # rules as the only isolation. Phase 2 makes this private.
    with Cluster("Default VPC — public subnets, no NAT, single AZ (eu-central-1a); isolation by security groups"):
        alb = ALB("ALB (2 AZs, as required)\nX-Edge-Secret\n(cannot bypass WAF)")
        with Cluster("ECS Fargate — one task per bounded context, public IP, JWT verified in each"):
            waiting = ECS("waiting-room")
            seats = ECS("seat-inventory")
            checkout = ECS("checkout-payment")
            issuance = ECS("ticket-issuance")
            notify = ECS("notification")
        with Cluster("Session-only data, SG → SG rules"):
            redis = ElastiCache("Redis\nqueue sorted sets\nINCR + ZPOPMIN")
            rds = RDS("Postgres (single AZ)\ncheckout db · ticketing db\nconstraints, not checks")

    with Cluster("Regional data — outside the VPC, $0 standing"):
        ddb = Dynamodb("DynamoDB seat-inventory\nconditional writes\nTTL on holds")
        conn = Dynamodb("DynamoDB\nsocket connections")
        s3 = S3("S3\nticket QR objects")

    with Cluster("Messaging"):
        q_issue = SQS("ticket-issuance-queue")
        q_qr = SQS("qr-generation-queue")
        topic = SNS("purchase-confirmed")
        q_notify = SQS("notifications-queue")
        q_admit = SQS("queue-admissions")

    with Cluster("Lambda — container images, no Spring"):
        cleanup = Lambda("hold-cleanup")
        qr = Lambda("qr-generation")
        notifier = Lambda("queue-socket\nadmission notifier")

    scheduler = Eventbridge("EventBridge Scheduler\nevery minute")
    ses = SES("SES")

    # The edge
    buyer >> Edge(label="sign in") >> cognito
    buyer >> Edge(label="Bearer access token") >> waf >> apigw >> alb
    buyer >> Edge(label="wss, one socket per tab") >> wsapi
    wsapi >> Edge(label="$connect / $disconnect") >> conn
    alb >> [waiting, seats, checkout, issuance]

    # The saga: started by the purchase route, and calling the services back through the
    # same stage with its own token — one edge, both directions, so the labels do not collide.
    apigw >> Edge(
        label="POST /purchase (ID token) → StartExecution, no Lambda\n"
              "← http:invoke via /{proxy+}, Bearer M2M token",
        dir="both",
    ) >> saga
    cognito >> Edge(label="client_credentials", style="dotted") >> saga
    saga >> Edge(label="sendMessage") >> q_issue >> issuance
    saga >> Edge(label="publish") >> topic >> q_notify >> notify >> ses

    # Stores
    waiting >> redis
    seats >> ddb
    checkout >> rds
    issuance >> rds
    issuance >> Edge(label="presigned GET", style="dashed") >> s3

    # Async edges
    issuance >> Edge(label="ticket id") >> q_qr >> qr >> s3
    scheduler >> cleanup >> ddb
    waiting >> Edge(label="admitted tokens") >> q_admit >> notifier
    notifier >> conn
    notifier >> Edge(label="postToConnection") >> wsapi
