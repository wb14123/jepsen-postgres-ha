# jepsen-postgres-drbd

Test postgres setup with Jepsen

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
lein run test --nodes-file ./nodes --username vagrant
```

