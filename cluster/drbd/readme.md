
# Create a DRBD cluster

## Install drbd

Run `./install-drbd.sh` script. It should already have been done by Vagrant.

Verify the kernel module are loaded:

```
lsmod | grep drbd
```

Verify the kernel module version:

```
sudo modinfo drbd
```


## Config DRBD

TODO: automate these steps into Vagrantfile.

Copy `./drbd.conf` to `/etc/drbd.conf` on each machine.

Run the following command on each machine (as root user), replace `db` with your resource name if needed:

```
drbdadm create-md db
drbdadm up db
```

With `drbdadm status db`, you should see something like this:

```
db role:Secondary
  disk:Inconsistent quorum:no open:no
  postgres-server-2 role:Secondary
    peer-disk:Inconsistent
  postgres-server-3 role:Secondary
    peer-disk:Inconsistent
```

You can see all the nodes are secondary since the data is not synced yet.

To initialize the resource, on **only one machine**, run:

```
drbdadm primary --force db
```

Then enable quorum in `drbd.conf` by replacing `quorum no` to `quorum majority`. Copy it to all the machines and run:

```
drbdadm adjust db
```

