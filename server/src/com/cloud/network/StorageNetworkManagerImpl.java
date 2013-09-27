// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.network;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import javax.ejb.Local;
import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.cloudstack.api.command.admin.network.CreateStorageNetworkIpRangeCmd;
import org.apache.cloudstack.api.command.admin.network.DeleteStorageNetworkIpRangeCmd;
import org.apache.cloudstack.api.command.admin.network.ListStorageNetworkIpRangeCmd;
import org.apache.cloudstack.api.command.admin.network.UpdateStorageNetworkIpRangeCmd;
import org.apache.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.dc.HostPodVO;
import com.cloud.dc.StorageNetworkIpAddressVO;
import com.cloud.dc.StorageNetworkIpRange;
import com.cloud.dc.StorageNetworkIpRangeVO;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.dc.dao.StorageNetworkIpAddressDao;
import com.cloud.dc.dao.StorageNetworkIpRangeDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.utils.db.GenericQueryBuilder;
import com.cloud.utils.db.GenericQueryBuilder;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.SecondaryStorageVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.SecondaryStorageVmDao;

@Component
@Local(value = { StorageNetworkManager.class, StorageNetworkService.class })
public class StorageNetworkManagerImpl extends ManagerBase implements StorageNetworkManager, StorageNetworkService {
    private static final Logger s_logger = Logger.getLogger(StorageNetworkManagerImpl.class);

    @Inject
    StorageNetworkIpAddressDao _sNwIpDao;
    @Inject
    StorageNetworkIpRangeDao _sNwIpRangeDao;
    @Inject
    NetworkDao _networkDao;
    @Inject
    HostPodDao _podDao;
    @Inject
    SecondaryStorageVmDao _ssvmDao;

    private void checkOverlapPrivateIpRange(long podId, String startIp, String endIp) {
        HostPodVO pod = _podDao.findById(podId);
        if (pod == null) {
            throw new CloudRuntimeException("Cannot find pod " + podId);
        }
        String[] IpRange = pod.getDescription().split("-");
        if ((IpRange[0] == null || IpRange[1] == null) || (!NetUtils.isValidIp(IpRange[0]) || !NetUtils.isValidIp(IpRange[1]))) {
            return;
        }
        if (NetUtils.ipRangesOverlap(startIp, endIp, IpRange[0], IpRange[1])) {
            throw new InvalidParameterValueException("The Storage network Start IP and endIP address range overlap with private IP :" + IpRange[0] + ":" + IpRange[1]);
        }
    }

    private void checkOverlapStorageIpRange(long podId, String startIp, String endIp) {
        List<StorageNetworkIpRangeVO> curRanges = _sNwIpRangeDao.listByPodId(podId);
        for (StorageNetworkIpRangeVO range : curRanges) {
            if (NetUtils.ipRangesOverlap(startIp, endIp, range.getStartIp(), range.getEndIp())) {
                throw new InvalidParameterValueException("The Storage network Start IP and endIP address range overlap with private IP :" + range.getStartIp() + " - " + range.getEndIp());
            }
        }
    }

    private void createStorageIpEntires(Transaction txn, long rangeId, String startIp, String endIp, long zoneId) throws SQLException {
        long startIPLong = NetUtils.ip2Long(startIp);
        long endIPLong = NetUtils.ip2Long(endIp);
        String insertSql = "INSERT INTO `cloud`.`op_dc_storage_network_ip_address` (range_id, ip_address, mac_address, taken) VALUES (?, ?, (select mac_address from `cloud`.`data_center` where id=?), ?)";
        String updateSql = "UPDATE `cloud`.`data_center` set mac_address = mac_address+1 where id=?";
        PreparedStatement stmt = null;
        Connection conn = txn.getConnection();

        while (startIPLong <= endIPLong) {
            stmt = conn.prepareStatement(insertSql);
            stmt.setLong(1, rangeId);
            stmt.setString(2, NetUtils.long2Ip(startIPLong++));
            stmt.setLong(3, zoneId);
            stmt.setNull(4, java.sql.Types.DATE);
            stmt.executeUpdate();
            stmt.close();

            stmt = txn.prepareStatement(updateSql);
            stmt.setLong(1, zoneId);
            stmt.executeUpdate();
            stmt.close();
        }
    }

    @Override
    @DB
    public StorageNetworkIpRange updateIpRange(UpdateStorageNetworkIpRangeCmd cmd) {
        Integer vlan = cmd.getVlan();
        Long rangeId = cmd.getId();
        String startIp = cmd.getStartIp();
        String endIp = cmd.getEndIp();
        String netmask = cmd.getNetmask();

        if (netmask != null && !NetUtils.isValidNetmask(netmask)) {
            throw new CloudRuntimeException("Invalid netmask:" + netmask);
        }

        if (_sNwIpDao.countInUseIpByRangeId(rangeId) > 0) {
            throw new CloudRuntimeException("Cannot update the range," + getInUseIpAddress(rangeId));
        }

        StorageNetworkIpRangeVO range = _sNwIpRangeDao.findById(rangeId);
        if (range == null) {
            throw new CloudRuntimeException("Cannot find storage ip range " + rangeId);
        }

        if (startIp != null || endIp != null) {
            long podId = range.getPodId();
            startIp = startIp == null ? range.getStartIp() : startIp;
            endIp = endIp == null ? range.getEndIp() : endIp;
            checkOverlapPrivateIpRange(podId, startIp, endIp);
            checkOverlapStorageIpRange(podId, startIp, endIp);
        }

        Transaction txn = Transaction.currentTxn();
        txn.start();
        try {
            range = _sNwIpRangeDao.acquireInLockTable(range.getId());
            if (range == null) {
                throw new CloudRuntimeException("Cannot acquire lock on storage ip range " + rangeId);
            }
            StorageNetworkIpRangeVO vo = _sNwIpRangeDao.createForUpdate();
            if (vlan != null) {
                vo.setVlan(vlan);
            }
            if (startIp != null) {
                vo.setStartIp(startIp);
            }
            if (endIp != null) {
                vo.setEndIp(endIp);
            }
            if (netmask != null) {
                vo.setNetmask(netmask);
            }
            _sNwIpRangeDao.update(rangeId, vo);
        } finally {
            if (range != null) {
                _sNwIpRangeDao.releaseFromLockTable(range.getId());
            }
        }
        txn.commit();

        return _sNwIpRangeDao.findById(rangeId);
    }

    @Override
    @DB
    public StorageNetworkIpRange createIpRange(CreateStorageNetworkIpRangeCmd cmd) throws SQLException {
        Long podId = cmd.getPodId();
        String startIp = cmd.getStartIp();
        String endIp = cmd.getEndIp();
        Integer vlan = cmd.getVlan();
        String netmask = cmd.getNetmask();

        if (endIp == null) {
            endIp = startIp;
        }

        if (!NetUtils.isValidNetmask(netmask)) {
            throw new CloudRuntimeException("Invalid netmask:" + netmask);
        }

        HostPodVO pod = _podDao.findById(podId);
        if (pod == null) {
            throw new CloudRuntimeException("Cannot find pod " + podId);
        }
        Long zoneId = pod.getDataCenterId();

        List<NetworkVO> nws = _networkDao.listByZoneAndTrafficType(zoneId, TrafficType.Storage);
        if (nws.size() == 0) {
            throw new CloudRuntimeException("Cannot find storage network in zone " + zoneId);
        }
        if (nws.size() > 1) {
            throw new CloudRuntimeException("Find more than one storage network in zone " + zoneId + "," + nws.size() + " found");
        }
        NetworkVO nw = nws.get(0);

        checkOverlapPrivateIpRange(podId, startIp, endIp);
        checkOverlapStorageIpRange(podId, startIp, endIp);

        Transaction txn = Transaction.currentTxn();
        StorageNetworkIpRangeVO range = null;

        txn.start();
        range = new StorageNetworkIpRangeVO(zoneId, podId, nw.getId(), startIp, endIp, vlan, netmask, cmd.getGateWay());
        _sNwIpRangeDao.persist(range);
        try {
            createStorageIpEntires(txn, range.getId(), startIp, endIp, zoneId);
        } catch (SQLException e) {
            txn.rollback();
            StringBuilder err = new StringBuilder();
            err.append("Create storage network range failed.");
            err.append("startIp=" + startIp);
            err.append("endIp=" + endIp);
            err.append("netmask=" + netmask);
            err.append("zoneId=" + zoneId);
            s_logger.debug(err.toString(), e);
            throw e;
        }

        txn.commit();

        return range;
    }

    private String getInUseIpAddress(long rangeId) {
        List<String> ips = _sNwIpDao.listInUseIpByRangeId(rangeId);
        StringBuilder res = new StringBuilder();
        res.append("Below IP of range " + rangeId + " is still in use:");
        for (String ip : ips) {
            res.append(ip).append(",");
        }
        return res.toString();
    }

    @Override
    @DB
    public void deleteIpRange(DeleteStorageNetworkIpRangeCmd cmd) {
        long rangeId = cmd.getId();
        StorageNetworkIpRangeVO range = _sNwIpRangeDao.findById(rangeId);
        if (range == null) {
            throw new CloudRuntimeException("Can not find storage network ip range " + rangeId);
        }

        if (_sNwIpDao.countInUseIpByRangeId(rangeId) > 0) {
            throw new CloudRuntimeException(getInUseIpAddress(rangeId));
        }

        final Transaction txn = Transaction.currentTxn();
        txn.start();
        try {
            range = _sNwIpRangeDao.acquireInLockTable(rangeId);
            if (range == null) {
                String msg = "Unable to acquire lock on storage network ip range id=" + rangeId + ", delete failed";
                s_logger.warn(msg);
                throw new CloudRuntimeException(msg);
            }
            /*
             * entries in op_dc_storage_network_ip_address will be deleted automatically due to
             * fk_storage_ip_address__range_id constraint key
             */
            _sNwIpRangeDao.remove(rangeId);
        } finally {
            if (range != null) {
                _sNwIpRangeDao.releaseFromLockTable(rangeId);
            }
        }
        txn.commit();
    }

    @Override
    public List<StorageNetworkIpRange> listIpRange(ListStorageNetworkIpRangeCmd cmd) {
        Long rangeId = cmd.getRangeId();
        Long podId = cmd.getPodId();
        Long zoneId = cmd.getZoneId();

        List result = null;
        if (rangeId != null) {
            result = _sNwIpRangeDao.listByRangeId(rangeId);
        } else if (podId != null) {
            result = _sNwIpRangeDao.listByPodId(podId);
        } else if (zoneId != null) {
            result = _sNwIpRangeDao.listByDataCenterId(zoneId);
        } else {
            result = _sNwIpRangeDao.listAll();
        }

        return result;
    }

    @Override
    public void releaseIpAddress(String ip) {
        _sNwIpDao.releaseIpAddress(ip);
    }

    @Override
    public StorageNetworkIpAddressVO acquireIpAddress(long podId) {
        List<StorageNetworkIpRangeVO> ranges = _sNwIpRangeDao.listByPodId(podId);
        for (StorageNetworkIpRangeVO r : ranges) {
            try {
                Long rangeId = r.getId();
                r = _sNwIpRangeDao.acquireInLockTable(rangeId);
                if (r == null) {
                    String msg = "Unable to acquire lock on storage network ip range id=" + rangeId + ", delete failed";
                    s_logger.warn(msg);
                    throw new CloudRuntimeException(msg);
                }

                StorageNetworkIpAddressVO ip = _sNwIpDao.takeIpAddress(r.getId());
                if (ip != null) {
                    return ip;
                }
            } finally {
                if (r != null) {
                    _sNwIpRangeDao.releaseFromLockTable(r.getId());
                }
            }
        }

        return null;
    }

    @Override
    public boolean isStorageIpRangeAvailable(long zoneId) {
        GenericQueryBuilder<StorageNetworkIpRangeVO, StorageNetworkIpRangeVO> sc = GenericQueryBuilder.create(StorageNetworkIpRangeVO.class);
        sc.and(sc.entity().getDataCenterId(), Op.EQ, zoneId);
        List<StorageNetworkIpRangeVO> entries = sc.list();
        return entries.size() > 0;
    }

    @Override
    public List<SecondaryStorageVmVO> getSSVMWithNoStorageNetwork(long zoneId) {
        List<SecondaryStorageVmVO> ssvms = _ssvmDao.getSecStorageVmListInStates(null, zoneId, VirtualMachine.State.Starting, VirtualMachine.State.Running, VirtualMachine.State.Stopping);
        return ssvms;
    }

    @Override
    public boolean isAnyStorageIpInUseInZone(long zoneId) {
        List<StorageNetworkIpRangeVO> ranges = _sNwIpRangeDao.listByDataCenterId(zoneId);
        for (StorageNetworkIpRangeVO r : ranges) {
            if (_sNwIpDao.countInUseIpByRangeId(r.getId()) > 0) {
                return true;
            }
        }
        return false;
    }
}
