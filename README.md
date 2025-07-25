# jepsen-postgres-ha

Test different postgres HA setup with Jepsen.

## Overview

This project create a VM cluster using Vagrant. Then install a Kubernetes cluster using k3s. Related code is defined under `./cluster`.

With a Kubernetes cluster, we can deploy different postgres HA solutions to it and test with Jepsen without change much of the Jepsen's code. Each HA solution is defined in a sub directory under `./cluster`.

Currently it has:

* single-node: just deploy postgres on a single node.
* patroni: deploy a postgres HA cluster with [patroni](https://github.com/patroni/patroni).

The cluster can be selected with cli option `--cluster`.


## Usage

### Requirements

* JDK >= 21
* [Leiningen](https://leiningen.org/)
* [Vagrant](https://www.vagrantup.com/) and Virtual Box.

### Prepare VMs

VMs are created by Vagrant.

First, create dir for disks:

```
mkdir -p /data/vagrant/disks/
```

Go to the cluster directory:

```
cd ./cluster
```

Then start Vagrant VMs:

```
vagrant up
```

Reboot VMs to refresh kernel and newly installed kernel modules (drbd):

```
vagrant halt && vagrant up
```

### Shudown/Destroy VMs

Use this command to shutdown VMs:

```
vagrant halt
```

If want to test with clean state, cleanup all the VMs and data:

```
vagrant destroy -f
```


### Run Test

Once the VMs are started, Jepsen tests can be run with `lein run test-all ...`. Use `--cluster` to select the HA solution to test. For example:

```
lein run test-all --nodes-file ./nodes --username vagrant -w append --concurrency 50 --isolation serializable --nemesis none  --time-limit 120   -r 200 --max-writes-per-key 16 --cluster single-node
```

With nemesis:

```
lein run test-all --nodes-file ./nodes --username vagrant -w append --concurrency 50 --isolation serializable --nemesis all  --time-limit 180   -r 200 --max-writes-per-key 16 --nemesis-interval 30 --cluster single-node
```

### Test Result

#### Patroni failed:

It doesn't reproduce every time. Use this command try multiple times. It will try 10 times of the test, 30 minutes each:

```bash
for i in `seq 1 10` ; do
  lein run test --nodes-file ./nodes --username vagrant -w append --concurrency 10 --isolation serializable --nemesis packet,kill --time-limit 1800 -r 100 --nemesis-interval 60 --break-conn-percent 0.8 --cluster patroni --key-count 1 --max-txn-length 1 --max-writes-per-key 24000 --nemesis-suite slow-net-kill
  sleep 30
done
```

Use `lein run serve` to start a web server to view the test results. Usually there will be 1 failed test.

Patroni has 2 problems now:

1. Can be seen from failed test, it doesn't guarantee read committed.
2. Failed to recover with 2/3 available nodes after multiple failures.
