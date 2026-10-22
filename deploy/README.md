# VM deployment

This deployment uses an Ubuntu 24.04 VM with 4 GB RAM or more, on x86_64 or
arm64. The tested images are multi-arch. It is intended for a small demo, not
a production security boundary.

## AWS EC2

Create a `t3.medium` EC2 instance with Ubuntu 24.04. In its security group,
allow:

- SSH (22) only from your own public IP address.
- HTTP (80) from anywhere (`0.0.0.0/0`).

Keep TCP 8080 closed in the security group. Do not expose ports 3000, 9090, or
3306. Grafana and Prometheus are bound to localhost on the instance and are
accessed through an SSH tunnel.

## 2. Install Docker

SSH to the instance and install Docker using Docker's official Ubuntu
instructions. Verify the installation:

```bash
docker --version
docker compose version
```

If the Docker package creates a `docker` group, log out and back in after
adding your user so Docker commands work without `sudo`.

## 3. Clone and configure the application

Clone the repository and enter it:

```bash
git clone https://github.com/dev-saheblalshaikh/distributed-job-scheduler.git
cd distributed-job-scheduler
cp deploy/.env.example deploy/.env
nano deploy/.env
```

Replace every example password with a long, unique value. The deployment
overlay reads these values from `deploy/.env`.

## 4. Start the stack

Run the command from the repository root. Compose loads the environment file
explicitly because it is stored under `deploy/`:

```bash
docker compose --env-file deploy/.env -f docker-compose.yml -f deploy/docker-compose.prod.yml up -d --build --scale worker=3
```

Check the containers and API health:

```bash
docker compose --env-file deploy/.env -f docker-compose.yml -f deploy/docker-compose.prod.yml ps
curl http://localhost:8080/actuator/health
```

The API is available through the public proxy at `http://INSTANCE_PUBLIC_IP`.
Port 8080 is published only on the VM loopback interface, so it remains closed
to the internet. This stack is a demo and is not a production security
boundary. MySQL is published on
`127.0.0.1:3306` only (not reachable from outside the instance), and
the named `mysql-data` volume preserves its data across container recreation.

## Verify the public surface

Run these checks from a machine outside the VM. The expected result is shown
in each comment:

```bash
curl -i http://VM_IP/                         # 200 (the page)
curl -i http://VM_IP/demo/state               # 200 JSON
curl -i http://VM_IP/api/jobs                  # 404
curl -i http://VM_IP/actuator/prometheus       # 404
curl -i http://VM_IP:8080/                     # connection refused / timeout
```

The page is served over plain HTTP; HTTPS needs a domain.

If the first start used the wrong `.env`, MySQL keeps the original credentials
in its data volume. Recreate the volume and start again:

```bash
docker compose --env-file deploy/.env -f docker-compose.yml -f deploy/docker-compose.prod.yml down -v
docker compose --env-file deploy/.env -f docker-compose.yml -f deploy/docker-compose.prod.yml up -d --build --scale worker=3
```

## Oracle Cloud Always Free (Ampere A1)

Create an Ampere A1 VM with shape `VM.Standard.A1.Flex`, 2 OCPU and 12 GB
memory, using Ubuntu 24.04 aarch64. In the security list, allow SSH (22) only
from your own IP address. Also check the VM's own firewall, such as iptables,
because cloud security rules and the guest firewall both need to allow the
intended traffic.

Install Docker, clone the repository, create `deploy/.env`, and use the same
Compose command above. The application images used by this project are
multi-architecture images for x86_64 and arm64.

## Run the smoke test on the VM

Install PowerShell 7 from Microsoft's official Ubuntu 24.04 package
repository. The same repository commands work on both x64 and arm64 Ubuntu:

```bash
sudo apt-get update
sudo apt-get install -y wget
wget -q https://packages.microsoft.com/config/ubuntu/24.04/packages-microsoft-prod.deb
sudo dpkg -i packages-microsoft-prod.deb
rm packages-microsoft-prod.deb
sudo apt-get update
sudo apt-get install -y powershell
```

Load the deployment environment so the smoke test can read the database and
Grafana credentials:

```bash
set -a
source deploy/.env
set +a
```

Run the smoke test with the production Compose files and three workers. Three
workers are required because the smoke test expects four Prometheus targets
(API plus three workers), and S3 requires at least ten RUNNING jobs:

```bash
pwsh scripts/smoke-test.ps1 -IncludeCrashTest -ComposeFiles \
  "--env-file","deploy/.env","-f","docker-compose.yml","-f","deploy/docker-compose.prod.yml"
```

## Access Grafana through an SSH tunnel

From your local computer, forward Grafana and Prometheus through SSH:

```bash
ssh -N \
  -L 3000:127.0.0.1:3000 \
  -L 9090:127.0.0.1:9090 \
  ubuntu@INSTANCE_PUBLIC_IP
```

Open `http://localhost:3000` locally. Log in as `admin` with the value of
`GF_SECURITY_ADMIN_PASSWORD` from `deploy/.env`. Prometheus is available at
`http://localhost:9090` through the same tunnel.

## Cost and shutdown

Create a billing budget and a `$10` monthly cost alert in AWS Budgets. Set an
email notification so the account owner is warned before spending exceeds the
intended demonstration budget. A budget alert does not automatically stop
resources, so check the instance and related resources yourself.

When the application is not needed, stop the VM to save compute credits:

```bash
docker compose --env-file deploy/.env -f docker-compose.yml -f deploy/docker-compose.prod.yml down
```

Then stop the instance from the cloud console or CLI. On AWS, a stopped
instance still bills for its disk and any allocated Elastic IP. Release an
Elastic IP when it is no longer needed.

## Troubleshooting

On the first build, if Maven runs out of memory, add a 2 GB swap file:

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile swap swap defaults 0 0' | sudo tee -a /etc/fstab
```
