# Ubuntu EC2 deployment

This deployment uses one Ubuntu EC2 instance for the API, MySQL, Prometheus,
Grafana, and workers. It is intended for a small demonstration environment,
not a production AWS architecture.

## 1. Create the instance

Create a `t3.medium` EC2 instance with 4 GB RAM and Ubuntu. In its security
group, allow:

- SSH (22) only from your own public IP address.
- TCP 8080 from the internet only if the API must be publicly reachable.

Do not expose ports 3000, 9090, or 3306. Grafana and Prometheus are bound to
localhost on the instance and are accessed through an SSH tunnel.

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
docker compose --env-file deploy/.env -f docker-compose.yml -f deploy/docker-compose.prod.yml up -d --build --scale worker=2
```

Check the containers and API health:

```bash
docker compose --env-file deploy/.env -f docker-compose.yml -f deploy/docker-compose.prod.yml ps
curl http://localhost:8080/actuator/health
```

The API is available at `http://INSTANCE_PUBLIC_IP:8080` only when port 8080
was intentionally allowed in the security group. MySQL has no host port, and
the named `mysql-data` volume preserves its data across container recreation.

## 5. Access Grafana through an SSH tunnel

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

## 6. AWS cost controls

Create a billing budget and a `$10` monthly cost alert in AWS Budgets. Set an
email notification so the account owner is warned before spending exceeds the
intended demonstration budget. A budget alert does not automatically stop
resources, so check the instance and related resources yourself.

When the application is not needed, stop the EC2 instance to save compute
credits:

```bash
docker compose --env-file deploy/.env -f docker-compose.yml -f deploy/docker-compose.prod.yml down
```

Then stop the instance from the EC2 console or AWS CLI. Stopping the instance
does not delete the EBS volume, so storage charges can continue. An Elastic IP
also costs money while it is allocated but not attached to a running
instance; release it when it is no longer needed.
