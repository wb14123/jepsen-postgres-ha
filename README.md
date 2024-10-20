# jepsen-postgres-ha

Test different postgres HA setup with Jepsen.


## Usage

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

Once the VMs are started, Jepsen tests can be run with

```
lein run test-all --nodes-file ./nodes --username vagrant -w append --concurrency 50 --isolation serializable --nemesis none  --time-limit 120   -r 200 --max-writes-per-key 16
```

With nemesis:

```
lein run test-all --nodes-file ./nodes --username vagrant -w append --concurrency 50 --isolation serializable --nemesis all  --time-limit 60   -r 200 --max-writes-per-key 16
```
