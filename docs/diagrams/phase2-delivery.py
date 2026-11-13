"""Phase 2 stacks, state and delivery with official icons — docs/phase2-plan.md §2.

The Mermaid in phase2-plan.md §2 is the source of truth. Regenerate after any change there:

    docker run --rm -v "$PWD:/out" gtramontina/diagrams:0.23.4 docs/diagrams/phase2-delivery.py
"""
from diagrams import Cluster, Diagram, Edge
from diagrams.aws.compute import ECR
from diagrams.aws.cost import Budgets
from diagrams.aws.devtools import CommandLineInterface
from diagrams.aws.general import GenericSamlToken
from diagrams.aws.management import Organizations, SystemsManagerParameterStore
from diagrams.aws.security import IAMRole, SingleSignOn
from diagrams.aws.storage import S3
from diagrams.onprem.ci import GithubActions
from diagrams.onprem.iac import Terraform
from diagrams.onprem.vcs import Github

GRAPH = {
    "fontsize": "22",
    "fontname": "Sans-Serif",
    "labelloc": "t",
    "pad": "0.6",
    "nodesep": "0.5",
    "ranksep": "1.2",
}
NODE = {"fontsize": "13", "fontname": "Sans-Serif"}
EDGE = {"fontsize": "11", "fontname": "Sans-Serif"}

with Diagram(
    "ticket-waiting-room — Phase 2 stacks and delivery (no static AWS keys)",
    filename="docs/diagrams/phase2-delivery",
    outformat="png",
    show=False,
    direction="LR",
    graph_attr=GRAPH,
    node_attr=NODE,
    edge_attr=EDGE,
):
    owner = CommandLineInterface("owner\nSSO session\n(bootstrap only)")

    with Cluster("management account — no workloads, no CI"):
        idc = SingleSignOn("IAM Identity Center\npermission sets")
        scp = Organizations("SCPs on OU workloads\ninfra/org (by hand)")

    with Cluster("GitHub — public repo, account id masked in logs"):
        repo = Github("repo\nruleset-protected main")
        with Cluster("workflows"):
            ci = GithubActions("ci · images\n(affected, arm64 → digest)")
            tf = GithubActions("terraform\nplan on PR · apply on main")
            session = GithubActions("session\napply → migrate →\nsmoke → destroy")
            reaper = GithubActions("session-reaper (hourly)\ndestroy after expires_at\n+ drift (nightly)")

    with Cluster("member account twr-dev (OU workloads)"):
        oidc = GenericSamlToken("IAM OIDC provider\ntoken.actions.githubusercontent.com")
        images_role = IAMRole("ci-images\nECR push + /images/* only")
        plan_role = IAMRole("tf-plan\nPRs + drift, no data-plane reads")
        apply_f = IAMRole("tf-apply-foundation\nenv 'foundation', reviewer\nIAM only under boundary")
        apply_s = IAMRole("tf-apply-session\nenv 'session'\nno IAM writes")
        images_ssm = SystemsManagerParameterStore("SSM /twr/dev/images/*\ndigests")
        state = S3("state bucket\nversioned, use_lockfile")
        ecr = ECR("ECR")
        ssm = SystemsManagerParameterStore("SSM parameters\n/twr/dev/foundation/*")
        budgets = Budgets("budgets\n$10/mo · $20 phase\n→ owner email")
        with Cluster("bootstrap (local state, then migrated)"):
            bootstrap = Terraform("bootstrap root")
        with Cluster("foundation — ≈ $2.5–3/month standing"):
            foundation = Terraform("foundation root")
        with Cluster("session — ≈ $0.30/h, toggles"):
            sess = Terraform("session root")

    owner >> Edge(label="SSO") >> idc
    idc >> Edge(label="admin (slices 0–3)\nterraform apply") >> bootstrap
    scp >> Edge(style="dotted", label="bounds every principal") >> oidc
    bootstrap >> [state, oidc, images_role, plan_role, apply_f, apply_s]
    repo >> [ci, tf, session]
    ci >> Edge(label="OIDC images.yml@main") >> images_role
    images_role >> Edge(label="push") >> ecr
    images_role >> Edge(label="digest") >> images_ssm
    tf >> Edge(label="OIDC (pull_request)") >> plan_role
    tf >> Edge(label="OIDC environment:foundation\n(approval)") >> apply_f
    [session, reaper] >> Edge(label="OIDC environment:session") >> apply_s
    oidc - Edge(style="dotted") - [images_role, plan_role, apply_f, apply_s]
    apply_f >> foundation
    apply_s >> sess
    foundation >> Edge(label="publishes outputs") >> ssm
    ssm >> Edge(label="data sources") >> sess
    images_ssm >> Edge(label="resolved at dispatch → -var") >> sess
    [foundation, sess] >> Edge(style="dotted", label="state") >> state
    foundation >> budgets
