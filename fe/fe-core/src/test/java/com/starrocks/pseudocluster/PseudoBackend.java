// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Inc.
package com.starrocks.pseudocluster;

import com.baidu.brpc.RpcContext;
import com.baidu.brpc.client.RpcCallback;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Queues;
import com.starrocks.catalog.DiskInfo;
import com.starrocks.common.AlreadyExistsException;
import com.starrocks.common.NotImplementedException;
import com.starrocks.common.UserException;
import com.starrocks.proto.PCancelPlanFragmentRequest;
import com.starrocks.proto.PCancelPlanFragmentResult;
import com.starrocks.proto.PExecBatchPlanFragmentsRequest;
import com.starrocks.proto.PExecBatchPlanFragmentsResult;
import com.starrocks.proto.PExecPlanFragmentRequest;
import com.starrocks.proto.PExecPlanFragmentResult;
import com.starrocks.proto.PFetchDataRequest;
import com.starrocks.proto.PFetchDataResult;
import com.starrocks.proto.PProxyRequest;
import com.starrocks.proto.PProxyResult;
import com.starrocks.proto.PQueryStatistics;
import com.starrocks.proto.PTabletInfo;
import com.starrocks.proto.PTabletWithPartition;
import com.starrocks.proto.PTabletWriterAddBatchResult;
import com.starrocks.proto.PTabletWriterAddChunkRequest;
import com.starrocks.proto.PTabletWriterCancelRequest;
import com.starrocks.proto.PTabletWriterCancelResult;
import com.starrocks.proto.PTabletWriterOpenRequest;
import com.starrocks.proto.PTabletWriterOpenResult;
import com.starrocks.proto.PTriggerProfileReportRequest;
import com.starrocks.proto.PTriggerProfileReportResult;
import com.starrocks.proto.PUniqueId;
import com.starrocks.proto.StatusPB;
import com.starrocks.rpc.PBackendServiceAsync;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.system.Backend;
import com.starrocks.system.SystemInfoService;
import com.starrocks.thrift.BackendService;
import com.starrocks.thrift.FrontendService;
import com.starrocks.thrift.FrontendServiceVersion;
import com.starrocks.thrift.HeartbeatService;
import com.starrocks.thrift.TAgentPublishRequest;
import com.starrocks.thrift.TAgentResult;
import com.starrocks.thrift.TAgentTaskRequest;
import com.starrocks.thrift.TBackend;
import com.starrocks.thrift.TBackendInfo;
import com.starrocks.thrift.TCancelPlanFragmentParams;
import com.starrocks.thrift.TCancelPlanFragmentResult;
import com.starrocks.thrift.TCloneReq;
import com.starrocks.thrift.TDataSink;
import com.starrocks.thrift.TDataSinkType;
import com.starrocks.thrift.TDeleteEtlFilesRequest;
import com.starrocks.thrift.TDisk;
import com.starrocks.thrift.TEtlState;
import com.starrocks.thrift.TExecBatchPlanFragmentsParams;
import com.starrocks.thrift.TExecPlanFragmentParams;
import com.starrocks.thrift.TExecPlanFragmentResult;
import com.starrocks.thrift.TExportState;
import com.starrocks.thrift.TExportStatusResult;
import com.starrocks.thrift.TExportTaskRequest;
import com.starrocks.thrift.TFetchDataParams;
import com.starrocks.thrift.TFetchDataResult;
import com.starrocks.thrift.TFinishTaskRequest;
import com.starrocks.thrift.THeartbeatResult;
import com.starrocks.thrift.TMasterInfo;
import com.starrocks.thrift.TMasterResult;
import com.starrocks.thrift.TMiniLoadEtlStatusRequest;
import com.starrocks.thrift.TMiniLoadEtlStatusResult;
import com.starrocks.thrift.TMiniLoadEtlTaskRequest;
import com.starrocks.thrift.TPlanNode;
import com.starrocks.thrift.TPlanNodeType;
import com.starrocks.thrift.TPublishVersionRequest;
import com.starrocks.thrift.TPushReq;
import com.starrocks.thrift.TPushType;
import com.starrocks.thrift.TReportExecStatusParams;
import com.starrocks.thrift.TReportExecStatusResult;
import com.starrocks.thrift.TReportRequest;
import com.starrocks.thrift.TRoutineLoadTask;
import com.starrocks.thrift.TScanBatchResult;
import com.starrocks.thrift.TScanCloseParams;
import com.starrocks.thrift.TScanCloseResult;
import com.starrocks.thrift.TScanNextBatchParams;
import com.starrocks.thrift.TScanOpenParams;
import com.starrocks.thrift.TScanOpenResult;
import com.starrocks.thrift.TScanRangeParams;
import com.starrocks.thrift.TSnapshotRequest;
import com.starrocks.thrift.TStatus;
import com.starrocks.thrift.TStatusCode;
import com.starrocks.thrift.TStorageMedium;
import com.starrocks.thrift.TTabletCommitInfo;
import com.starrocks.thrift.TTabletInfo;
import com.starrocks.thrift.TTabletStatResult;
import com.starrocks.thrift.TTaskType;
import com.starrocks.thrift.TTransmitDataParams;
import com.starrocks.thrift.TTransmitDataResult;
import com.starrocks.thrift.TUniqueId;
import io.netty.buffer.ByteBuf;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.thrift.TDeserializer;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

public class PseudoBackend {
    private static final Logger LOG = LogManager.getLogger(PseudoBackend.class);
    private static final int REPORT_INTERVAL_SEC = 5;
    public static final long PATH_HASH = 123456;

    private final PseudoCluster cluster;
    private final String runPath;
    private final String host;
    private final int brpcPort;
    private final int heartBeatPort;
    private final int beThriftPort;
    private final int httpPort;
    private final FrontendService.Iface frontendService;

    private final TBackend tBackend;
    private AtomicLong reportVersion = new AtomicLong(0);
    private final BeTabletManager tabletManager = new BeTabletManager(this);
    private final BeTxnManager txnManager = new BeTxnManager(this);
    private final BlockingQueue<TAgentTaskRequest> taskQueue = Queues.newLinkedBlockingQueue();
    private final Map<TTaskType, Set<Long>> taskSignatures = new EnumMap(TTaskType.class);
    private volatile boolean stopped = false;
    private Thread reportTabletsWorker;
    private Thread reportDisksWorker;
    private Thread reportTasksWorker;

    private volatile float writeFailureRate = 0.0f;
    private volatile float publishFailureRate = 0.0f;

    Backend be;
    HeartBeatClient heatBeatClient;
    BeThriftClient backendClient;
    PseudoPBackendService pBackendService;

    private AtomicLong nextRowsetId = new AtomicLong(0);

    private Random random;

    private static ThreadLocal<PseudoBackend> currentBackend = new ThreadLocal<>();

    public static PseudoBackend getCurrentBackend() {
        return currentBackend.get();
    }

    String genRowsetId() {
        return String.format("rowset-%d-%d", be.getId(), nextRowsetId.incrementAndGet());
    }

    public PseudoBackend(PseudoCluster cluster, String runPath, long backendId, String host, int brpcPort, int heartBeatPort,
                         int beThriftPort, int httpPort,
                         FrontendService.Iface frontendService) {
        this.cluster = cluster;
        this.runPath = runPath;
        this.host = host;
        this.brpcPort = brpcPort;
        this.heartBeatPort = heartBeatPort;
        this.beThriftPort = beThriftPort;
        this.httpPort = httpPort;
        this.frontendService = frontendService;
        this.random = new Random(backendId);

        this.be = new Backend(backendId, host, heartBeatPort);
        this.tBackend = new TBackend(host, beThriftPort, httpPort);

        Map<String, DiskInfo> disks = Maps.newHashMap();
        DiskInfo diskInfo1 = new DiskInfo(runPath + "/storage");
        // TODO: maintain disk info with tablet/rowset count
        diskInfo1.setTotalCapacityB(100000000000L);
        diskInfo1.setAvailableCapacityB(50000000000L);
        diskInfo1.setDataUsedCapacityB(20000000000L);
        diskInfo1.setPathHash(PATH_HASH);
        diskInfo1.setStorageMedium(TStorageMedium.SSD);
        disks.put(diskInfo1.getRootPath(), diskInfo1);
        be.setDisks(ImmutableMap.copyOf(disks));
        be.setAlive(true);
        be.setOwnerClusterName(SystemInfoService.DEFAULT_CLUSTER);
        be.setBePort(beThriftPort);
        be.setBrpcPort(brpcPort);
        be.setHttpPort(httpPort);
        GlobalStateMgr.getCurrentSystemInfo().addBackend(be);
        this.heatBeatClient = new HeartBeatClient();
        this.backendClient = new BeThriftClient();
        this.pBackendService = new PseudoPBackendService();

        reportTabletsWorker = new Thread(() -> {
            currentBackend.set(PseudoBackend.this);
            while (!stopped) {
                try {
                    Random r = new Random();
                    int wait = REPORT_INTERVAL_SEC + r.nextInt(7) - 3;
                    for (int i = 0; i < wait; i++) {
                        Thread.sleep(1000);
                        if (stopped) {
                            break;
                        }
                    }
                } catch (InterruptedException e) {
                    LOG.error("reportWorker interrupted", e);
                }
                if (stopped) {
                    break;
                }
                try {
                    reportTablets();
                } catch (Exception e) {
                    LOG.error("reportWorker error", e);
                }
            }
        }, "reportTablets-" + be.getId());
        reportTabletsWorker.start();

        reportDisksWorker = new Thread(() -> {
            currentBackend.set(PseudoBackend.this);
            while (!stopped) {
                try {
                    Random r = new Random();
                    int wait = REPORT_INTERVAL_SEC + r.nextInt(7) - 3;
                    for (int i = 0; i < wait; i++) {
                        Thread.sleep(1000);
                        if (stopped) {
                            break;
                        }
                    }
                } catch (InterruptedException e) {
                    LOG.error("reportWorker interrupted", e);
                }
                if (stopped) {
                    break;
                }
                try {
                    reportDisks();
                } catch (Exception e) {
                    LOG.error("reportWorker error", e);
                }
            }
        }, "reportDisks-" + be.getId());
        reportDisksWorker.start();

        reportTasksWorker = new Thread(() -> {
            currentBackend.set(PseudoBackend.this);
            while (!stopped) {
                try {
                    Random r = new Random();
                    int wait = REPORT_INTERVAL_SEC + r.nextInt(7) - 3;
                    for (int i = 0; i < wait; i++) {
                        Thread.sleep(1000);
                        if (stopped) {
                            break;
                        }
                    }
                } catch (InterruptedException e) {
                    LOG.error("reportWorker interrupted", e);
                }
                if (stopped) {
                    break;
                }
                try {
                    reportTasks();
                } catch (Exception e) {
                    LOG.error("reportWorker error", e);
                }
            }
        }, "reportTasks-" + be.getId());
        reportTasksWorker.start();
    }

    public String getHost() {
        return host;
    }

    public long getId() {
        return be.getId();
    }

    public BeTxnManager getTxnManager() {
        return txnManager;
    }

    public BeTabletManager getTabletManager() {
        return tabletManager;
    }

    public Tablet getTablet(long tabletId) {
        return tabletManager.getTablet(tabletId);
    }

    public void setWriteFailureRate(float rate) {
        writeFailureRate = rate;
    }

    public float getWriteFailureRate() {
        return writeFailureRate;
    }

    public void setPublishFailureRate(float rate) {
        publishFailureRate = rate;
    }

    public float getPublishFailureRate() {
        return publishFailureRate;
    }

    private void reportTablets() {
        // report tablets
        TReportRequest request = new TReportRequest();
        request.setTablets(tabletManager.getAllTabletInfo());
        request.setTablet_max_compaction_score(100);
        request.setBackend(tBackend);
        request.setReport_version(reportVersion.get());
        TMasterResult result;
        try {
            result = frontendService.report(request);
            LOG.info("report {} tablets", request.tablets.size());
        } catch (TException e) {
            LOG.error("report tablets error", e);
            return;
        }
    }

    private void reportDisks() {
        // report disks
        TReportRequest request = new TReportRequest();
        Map<String, TDisk> tdisks = new HashMap<>();
        ImmutableMap<String, DiskInfo> diskInfos = be.getDisks();
        for (Map.Entry<String, DiskInfo> entry : diskInfos.entrySet()) {
            TDisk tdisk = new TDisk();
            tdisk.setRoot_path(entry.getKey());
            tdisk.setPath_hash(entry.getValue().getPathHash());
            tdisk.setDisk_total_capacity(entry.getValue().getTotalCapacityB());
            tdisk.setDisk_available_capacity(entry.getValue().getAvailableCapacityB());
            tdisk.setData_used_capacity(entry.getValue().getDataUsedCapacityB());
            tdisk.setStorage_medium(entry.getValue().getStorageMedium());
            tdisk.setUsed(true);
            tdisks.put(entry.getKey(), tdisk);
        }
        request.setDisks(tdisks);
        request.setBackend(tBackend);
        request.setReport_version(reportVersion.get());
        TMasterResult result;
        try {
            result = frontendService.report(request);
            LOG.info("report {} disks", request.disks.size());
        } catch (TException e) {
            LOG.error("report disk error", e);
            return;
        }
    }

    private void reportTasks() {
        // report tasks
        TReportRequest request = new TReportRequest();
        request.setTasks(new HashMap<>());
        request.setBackend(tBackend);
        request.setReport_version(reportVersion.get());
        Map<TTaskType, Set<Long>> tasks = new HashMap<>();
        synchronized (taskSignatures) {
            for (TTaskType type : taskSignatures.keySet()) {
                HashSet<Long> ts = new HashSet<>();
                ts.addAll(taskSignatures.get(type));
                tasks.put(type, ts);
            }
        }
        request.setTasks(tasks);
        TMasterResult result;
        try {
            result = frontendService.report(request);
            LOG.info("report {} tasks", request.tasks.size());
        } catch (TException e) {
            LOG.error("report tasks error", e);
            return;
        }
    }

    public static TStatus toStatus(Exception e) {
        TStatus ts = new TStatus();
        ts.setError_msgs(Lists.newArrayList(e.getMessage()));
        if (e instanceof NotImplementedException) {
            ts.setStatus_code(TStatusCode.NOT_IMPLEMENTED_ERROR);
        } else if (e instanceof AlreadyExistsException) {
            ts.setStatus_code(TStatusCode.ALREADY_EXIST);
        } else {
            ts.setStatus_code(TStatusCode.INTERNAL_ERROR);
        }
        return ts;
    }

    void handleCreateTablet(TAgentTaskRequest request, TFinishTaskRequest finish) throws UserException {
        Tablet t = tabletManager.createTablet(request.create_tablet_req);
    }

    void handleDropTablet(TAgentTaskRequest request, TFinishTaskRequest finish) {
        tabletManager.dropTablet(request.drop_tablet_req.tablet_id, request.drop_tablet_req.force);
    }

    void handlePublish(TAgentTaskRequest request, TFinishTaskRequest finish) {
        cluster.getConfig().injectPublishTaskLatency();
        TPublishVersionRequest task = request.publish_version_req;
        txnManager.publish(task.transaction_id, task.partition_version_infos, finish);
    }

    void handleClone(TAgentTaskRequest request, TFinishTaskRequest finish) throws Exception {
        TCloneReq task = request.clone_req;
        Tablet destTablet = tabletManager.getTablet(task.tablet_id);
        if (destTablet == null) {
            throw new Exception("clone failed dest tablet " + task.tablet_id + " on " + be.getId() + " not found");
        }
        if (task.src_backends.size() != 1) {
            throw new Exception("bad src backends size " + task.src_backends.size());
        }
        TBackend backend = task.src_backends.get(0);
        PseudoBackend srcBackend = cluster.getBackendByHost(backend.host);
        if (srcBackend == null) {
            throw new Exception("clone failed src backend " + backend.host + " not found");
        }
        if (srcBackend.getId() == getId()) {
            throw new Exception("clone failed src backend " + backend.host + " is same as dest backend " + be.getId());
        }
        Tablet srcTablet = srcBackend.getTabletManager().getTablet(task.tablet_id);
        if (srcTablet == null) {
            throw new Exception("clone failed src tablet " + task.tablet_id + " on " + srcBackend.be.getId() + " not found");
        }
        // currently, only incremental clone is supported
        // TODO: add full clone support
        destTablet.cloneFrom(srcTablet);
        System.out.printf("clone tablet:%d %s -> %s %s\n", task.tablet_id, srcBackend.be.getId(), be.getId(),
                destTablet.versionInfo());
        finish.finish_tablet_infos = Lists.newArrayList(destTablet.getTabletInfo());
    }

    void handleTask(TAgentTaskRequest request) {
        TFinishTaskRequest finishTaskRequest = new TFinishTaskRequest(tBackend,
                request.getTask_type(), request.getSignature(), new TStatus(TStatusCode.OK));
        long v = reportVersion.incrementAndGet();
        finishTaskRequest.setReport_version(v);
        try {
            switch (finishTaskRequest.task_type) {
                case CREATE:
                    handleCreateTablet(request, finishTaskRequest);
                    break;
                case DROP:
                    handleDropTablet(request, finishTaskRequest);
                    break;
                case PUBLISH_VERSION:
                    handlePublish(request, finishTaskRequest);
                    break;
                case CLONE:
                    handleClone(request, finishTaskRequest);
                    break;
                case REALTIME_PUSH:
                    handleRealtimePush(request, finishTaskRequest);
                default:
                    LOG.info("ignore task type:" + finishTaskRequest.task_type + " signature:" + finishTaskRequest.signature);
            }
        } catch (Exception e) {
            LOG.warn("Exception in handleTask " + finishTaskRequest.task_type + " " + finishTaskRequest.signature, e);
            finishTaskRequest.setTask_status(toStatus(e));
        }
        try {
            frontendService.finishTask(finishTaskRequest);
        } catch (TException e) {
            LOG.warn("error call finishTask", e);
        }
        synchronized (taskSignatures) {
            Set<Long> signatures = taskSignatures.get(request.task_type);
            if (signatures != null) {
                signatures.remove(request.getSignature());
            }
        }
    }

    private void handleRealtimePush(TAgentTaskRequest request, TFinishTaskRequest finish) {
        TPushReq pushReq = request.getPush_req();
        TPushType pushType = pushReq.getPush_type();
        switch (pushType) {
            case DELETE:
                handleRealtimePushTypeDelete(pushReq, finish);
                break;
            default:
                throw new RuntimeException("pushType:" + pushType + " is not implement");
        }
    }

    private void handleRealtimePushTypeDelete(TPushReq pushReq, TFinishTaskRequest finish) {
        List<TTabletInfo> finish_tablet_infos = Lists.newArrayList();
        long tabletId = pushReq.getTablet_id();
        Tablet tablet = tabletManager.getTablet(tabletId);
        finish_tablet_infos.add(tablet.getTabletInfo());
        finish.setFinish_tablet_infos(finish_tablet_infos);
        finish.setRequest_version(pushReq.getVersion());
    }

    private class HeartBeatClient extends HeartbeatService.Client {
        public HeartBeatClient() {
            super(null);
        }

        @Override
        public THeartbeatResult heartbeat(TMasterInfo master_info) throws TException {
            TBackendInfo backendInfo = new TBackendInfo(beThriftPort, httpPort);
            backendInfo.setBrpc_port(brpcPort);
            return new THeartbeatResult(new TStatus(TStatusCode.OK), backendInfo);
        }
    }

    private class BeThriftClient extends BackendService.Client {

        BeThriftClient() {
            super(null);
            new Thread(() -> {
                currentBackend.set(PseudoBackend.this);
                while (true) {
                    try {
                        TAgentTaskRequest request = taskQueue.take();
                        handleTask(request);
                    } catch (InterruptedException e) {
                        LOG.warn("error get task", e);
                    }
                }
            }, "backend-worker-" + be.getId()).start();
        }

        @Override
        public TAgentResult submit_tasks(List<TAgentTaskRequest> tasks) {
            synchronized (taskSignatures) {
                for (TAgentTaskRequest task : tasks) {
                    Set<Long> signatures = taskSignatures.computeIfAbsent(task.getTask_type(), k -> new HashSet<>());
                    signatures.add(task.getSignature());
                }
            }
            taskQueue.addAll(tasks);
            return new TAgentResult(new TStatus(TStatusCode.OK));
        }

        @Override
        public TExecPlanFragmentResult exec_plan_fragment(TExecPlanFragmentParams params) {
            return null;
        }

        @Override
        public TCancelPlanFragmentResult cancel_plan_fragment(TCancelPlanFragmentParams params) {
            return null;
        }

        @Override
        public TTransmitDataResult transmit_data(TTransmitDataParams params) {
            return null;
        }

        @Override
        public TFetchDataResult fetch_data(TFetchDataParams params) {
            return null;
        }

        @Override
        public TAgentResult make_snapshot(TSnapshotRequest snapshot_request) {
            return new TAgentResult(new TStatus(TStatusCode.OK));
        }

        @Override
        public TAgentResult release_snapshot(String snapshot_path) {
            return new TAgentResult(new TStatus(TStatusCode.OK));
        }

        @Override
        public TAgentResult publish_cluster_state(TAgentPublishRequest request) {
            return new TAgentResult(new TStatus(TStatusCode.OK));
        }

        @Override
        public TAgentResult submit_etl_task(TMiniLoadEtlTaskRequest request) {
            return new TAgentResult(new TStatus(TStatusCode.OK));
        }

        @Override
        public TMiniLoadEtlStatusResult get_etl_status(TMiniLoadEtlStatusRequest request) {
            return new TMiniLoadEtlStatusResult(new TStatus(TStatusCode.OK), TEtlState.FINISHED);
        }

        @Override
        public TAgentResult delete_etl_files(TDeleteEtlFilesRequest request) {
            return new TAgentResult(new TStatus(TStatusCode.OK));
        }

        @Override
        public TStatus submit_export_task(TExportTaskRequest request) {
            return new TStatus(TStatusCode.OK);
        }

        @Override
        public TExportStatusResult get_export_status(TUniqueId task_id) {
            return new TExportStatusResult(new TStatus(TStatusCode.OK), TExportState.FINISHED);
        }

        @Override
        public TStatus erase_export_task(TUniqueId task_id) {
            return new TStatus(TStatusCode.OK);
        }

        @Override
        public TTabletStatResult get_tablet_stat() {
            TTabletStatResult stats = new TTabletStatResult();
            tabletManager.getTabletStat(stats);
            return stats;
        }

        @Override
        public TStatus submit_routine_load_task(List<TRoutineLoadTask> tasks) {
            return new TStatus(TStatusCode.OK);
        }

        @Override
        public TScanOpenResult open_scanner(TScanOpenParams params) {
            return null;
        }

        @Override
        public TScanBatchResult get_next(TScanNextBatchParams params) {
            return null;
        }

        @Override
        public TScanCloseResult close_scanner(TScanCloseParams params) {
            return null;
        }

    }

    private class PseudoPBackendService implements PBackendServiceAsync {
        private final ExecutorService executor = Executors.newSingleThreadExecutor();

        PseudoPBackendService() {
            executor.submit(() -> {
                currentBackend.set(PseudoBackend.this);
            });
        }

        @Override
        public PExecPlanFragmentResult execPlanFragment(PExecPlanFragmentRequest request) {
            PExecPlanFragmentResult result = new PExecPlanFragmentResult();
            StatusPB pStatus = new StatusPB();
            pStatus.statusCode = 0;
            result.status = pStatus;
            return result;
        }

        @Override
        public PExecBatchPlanFragmentsResult execBatchPlanFragments(PExecBatchPlanFragmentsRequest request) {
            PExecBatchPlanFragmentsResult result = new PExecBatchPlanFragmentsResult();
            StatusPB pStatus = new StatusPB();
            pStatus.statusCode = 0;
            result.status = pStatus;
            return result;
        }

        @Override
        public PCancelPlanFragmentResult cancelPlanFragment(PCancelPlanFragmentRequest request) {
            PCancelPlanFragmentResult result = new PCancelPlanFragmentResult();
            StatusPB pStatus = new StatusPB();
            pStatus.statusCode = 0;
            result.status = pStatus;
            return result;
        }

        @Override
        public PFetchDataResult fetchData(PFetchDataRequest request) {
            PFetchDataResult result = new PFetchDataResult();
            StatusPB pStatus = new StatusPB();
            pStatus.statusCode = 0;

            PQueryStatistics pQueryStatistics = new PQueryStatistics();
            pQueryStatistics.scanRows = 0L;
            pQueryStatistics.scanBytes = 0L;
            pQueryStatistics.cpuCostNs = 0L;
            pQueryStatistics.memCostBytes = 0L;

            result.status = pStatus;
            result.packetSeq = 0L;
            result.queryStatistics = pQueryStatistics;
            result.eos = true;
            return result;
        }

        @Override
        public PTriggerProfileReportResult triggerProfileReport(PTriggerProfileReportRequest request) {
            return null;
        }

        @Override
        public PProxyResult getInfo(PProxyRequest request) {
            return null;
        }

        @Override
        public Future<PExecPlanFragmentResult> execPlanFragment(
                PExecPlanFragmentRequest request, RpcCallback<PExecPlanFragmentResult> callback) {
            TDeserializer deserializer = new TDeserializer(new TBinaryProtocol.Factory());
            final TExecPlanFragmentParams params = new TExecPlanFragmentParams();
            PExecPlanFragmentResult result = new PExecPlanFragmentResult();
            result.status = new StatusPB();
            result.status.statusCode = 0;
            try {
                RpcContext rpcContext = RpcContext.getContext();
                ByteBuf buf = rpcContext.getRequestBinaryAttachment();
                byte[] serialRequest = new byte[buf.readableBytes()];
                buf.readBytes(serialRequest);
                deserializer.deserialize(params, serialRequest);
            } catch (TException e) {
                LOG.warn("error deserialize request", e);
                result.status.statusCode = TStatusCode.INTERNAL_ERROR.getValue();
                result.status.errorMsgs = Lists.newArrayList(e.getMessage());
                return CompletableFuture.completedFuture(result);
            }
            executor.submit(() -> {
                try {
                    execPlanFragmentWithReport(params);
                } catch (Exception e) {
                    LOG.warn("error execPlanFragment", e);
                }
            });
            return CompletableFuture.completedFuture(result);
        }

        @Override
        public Future<PExecBatchPlanFragmentsResult> execBatchPlanFragments(
                PExecBatchPlanFragmentsRequest request, RpcCallback<PExecBatchPlanFragmentsResult> callback) {
            TDeserializer deserializer = new TDeserializer(new TBinaryProtocol.Factory());
            final TExecBatchPlanFragmentsParams params = new TExecBatchPlanFragmentsParams();
            PExecBatchPlanFragmentsResult result = new PExecBatchPlanFragmentsResult();
            result.status = new StatusPB();
            result.status.statusCode = 0;
            try {
                RpcContext rpcContext = RpcContext.getContext();
                ByteBuf buf = rpcContext.getRequestBinaryAttachment();
                byte[] serialRequest = new byte[buf.readableBytes()];
                buf.readBytes(serialRequest);
                deserializer.deserialize(params, serialRequest);
            } catch (TException e) {
                LOG.warn("error deserialize request", e);
                result.status.statusCode = TStatusCode.INTERNAL_ERROR.getValue();
                result.status.errorMsgs = Lists.newArrayList(e.getMessage());
                return CompletableFuture.completedFuture(result);
            }
            executor.submit(() -> {
                try {
                    execBatchPlanFragmentsWithReport(params);
                } catch (Exception e) {
                    LOG.warn("error execBatchPlanFragments", e);
                }
            });
            return CompletableFuture.completedFuture(result);
        }

        @Override
        public Future<PCancelPlanFragmentResult> cancelPlanFragment(
                PCancelPlanFragmentRequest request, RpcCallback<PCancelPlanFragmentResult> callback) {
            return executor.submit(() -> {
                PCancelPlanFragmentResult result = new PCancelPlanFragmentResult();
                StatusPB pStatus = new StatusPB();
                pStatus.statusCode = 0;
                result.status = pStatus;
                return result;
            });
        }

        @Override
        public Future<PFetchDataResult> fetchData(PFetchDataRequest request, RpcCallback<PFetchDataResult> callback) {
            return executor.submit(() -> {
                PFetchDataResult result = new PFetchDataResult();
                StatusPB pStatus = new StatusPB();
                pStatus.statusCode = 0;

                PQueryStatistics pQueryStatistics = new PQueryStatistics();
                pQueryStatistics.scanRows = 0L;
                pQueryStatistics.scanBytes = 0L;
                pQueryStatistics.cpuCostNs = 0L;
                pQueryStatistics.memCostBytes = 0L;

                result.status = pStatus;
                result.packetSeq = 0L;
                result.queryStatistics = pQueryStatistics;
                result.eos = true;
                return result;
            });
        }

        @Override
        public Future<PTriggerProfileReportResult> triggerProfileReport(
                PTriggerProfileReportRequest request, RpcCallback<PTriggerProfileReportResult> callback) {
            return null;
        }

        @Override
        public Future<PProxyResult> getInfo(PProxyRequest request, RpcCallback<PProxyResult> callback) {
            return null;
        }
    }

    static TStatus status(TStatusCode code, String msg) {
        TStatus status = new TStatus();
        status.status_code = code;
        status.error_msgs = Lists.newArrayList(msg);
        return status;
    }

    void execPlanFragmentWithReport(TExecPlanFragmentParams params) {
        LOG.info("exec_plan_fragment {}", params.params.fragment_instance_id.toString());
        TReportExecStatusParams report = new TReportExecStatusParams();
        report.setProtocol_version(FrontendServiceVersion.V1);
        report.setQuery_id(params.params.query_id);
        report.setBackend_num(params.backend_num);
        report.setBackend_id(be.getId());
        report.setFragment_instance_id(params.params.fragment_instance_id);
        report.setDone(true);
        // TODO: support error injection
        report.setStatus(new TStatus(TStatusCode.OK));
        try {
            for (TPlanNode planNode : params.fragment.plan.nodes) {
                if (planNode.node_type == TPlanNodeType.OLAP_SCAN_NODE) {
                    List<TScanRangeParams> scanRanges = params.params.per_node_scan_ranges.get(planNode.getNode_id());
                    if (scanRanges == null) {
                        continue;
                    }
                    runOlapScan(report, planNode, params.params.per_node_scan_ranges.get(planNode.getNode_id()));
                }
            }
            if (params.fragment.output_sink != null) {
                TDataSink tDataSink = params.fragment.output_sink;
                runSink(report, tDataSink);
            }
        } catch (Throwable e) {
            e.printStackTrace();
            LOG.warn("error execPlanFragmentWithReport", e);
            report.setStatus(status(TStatusCode.INTERNAL_ERROR, e.getMessage()));
        }
        try {
            TReportExecStatusResult ret = frontendService.reportExecStatus(report);
            if (ret.status.status_code != TStatusCode.OK) {
                LOG.warn("error report exec status " + (ret.status.error_msgs.isEmpty() ? "" : ret.status.error_msgs.get(0)));
            }
        } catch (TException e) {
            LOG.warn("error report exec status", e);
        }
    }

    void execBatchPlanFragment(TExecPlanFragmentParams commonParams, TExecPlanFragmentParams uniqueParams) {
        TReportExecStatusParams report = new TReportExecStatusParams();
        report.setProtocol_version(FrontendServiceVersion.V1);
        report.setQuery_id(commonParams.params.query_id);
        report.setBackend_num(uniqueParams.backend_num);
        report.setBackend_id(be.getId());
        report.setFragment_instance_id(uniqueParams.params.fragment_instance_id);
        report.setDone(true);
        // TODO: support error injection
        report.setStatus(new TStatus(TStatusCode.OK));
        try {
            for (TPlanNode planNode : commonParams.fragment.plan.nodes) {
                if (planNode.node_type == TPlanNodeType.OLAP_SCAN_NODE) {
                    List<TScanRangeParams> scanRanges = uniqueParams.params.per_node_scan_ranges.get(planNode.getNode_id());
                    if (scanRanges == null) {
                        continue;
                    }
                    runOlapScan(report, planNode, scanRanges);
                }
            }
            if (uniqueParams.fragment.output_sink != null) {
                TDataSink tDataSink = uniqueParams.fragment.output_sink;
                runSink(report, tDataSink);
            } else if (commonParams.fragment.output_sink != null) {
                TDataSink tDataSink = commonParams.fragment.output_sink;
                runSink(report, tDataSink);
            }
        } catch (Throwable e) {
            e.printStackTrace();
            LOG.warn("error execBatchPlanFragment", e);
            report.setStatus(status(TStatusCode.INTERNAL_ERROR, e.getMessage()));
        }
        try {
            TReportExecStatusResult ret = frontendService.reportExecStatus(report);
            if (ret.status.status_code != TStatusCode.OK) {
                LOG.warn("error report exec status " + (ret.status.error_msgs.isEmpty() ? "" : ret.status.error_msgs.get(0)));
            }
        } catch (TException e) {
            LOG.warn("error report exec status", e);
        }
    }

    void execBatchPlanFragmentsWithReport(TExecBatchPlanFragmentsParams params) {
        LOG.info("exec_batch_plan_fragments {} #instances:{}", params.common_param.params.query_id.toString(),
                params.unique_param_per_instance.size());
        for (TExecPlanFragmentParams uniqueParams : params.unique_param_per_instance) {
            execBatchPlanFragment(params.common_param, uniqueParams);
        }
    }

    private void runOlapScan(TReportExecStatusParams report, TPlanNode olapScanNode, List<TScanRangeParams> tScanRangeParams) {
        for (TScanRangeParams scanRangeParams : tScanRangeParams) {
            long tablet_id = scanRangeParams.scan_range.internal_scan_range.tablet_id;
            long version = Long.parseLong(scanRangeParams.scan_range.internal_scan_range.version);
            Tablet tablet = tabletManager.getTablet(tablet_id);
            if (tablet == null) {
                report.setStatus(status(TStatusCode.INTERNAL_ERROR,
                        String.format("olapScan(be:%d tablet:%d version:%d) failed: tablet not found ", getId(), tablet_id,
                                version)));
                return;
            }
            try {
                tablet.read(version);
            } catch (Exception e) {
                report.setStatus(status(TStatusCode.INTERNAL_ERROR, e.getMessage()));
                return;
            }
        }
    }

    private void runSink(TReportExecStatusParams report, TDataSink tDataSink) {
        if (tDataSink.type != TDataSinkType.OLAP_TABLE_SINK) {
            return;
        }
        cluster.getConfig().injectTableSinkWriteLatency();
        PseudoOlapTableSink sink = new PseudoOlapTableSink(cluster, tDataSink);
        if (!sink.open()) {
            sink.cancel();
            report.status = status(TStatusCode.INTERNAL_ERROR,
                    String.format("open sink failed, backend:%s txn:%d", be.getId(), sink.txnId));
            return;
        }
        if (!sink.close()) {
            sink.cancel();
            report.status = status(TStatusCode.INTERNAL_ERROR,
                    String.format("open sink failed, backend:%s txn:%d %s", be.getId(), sink.txnId, sink.getErrorMessage()));
            return;
        }
        List<TTabletCommitInfo> commitInfos = sink.getTabletCommitInfos();
        report.setCommitInfos(commitInfos);
        report.setLoad_counters(sink.getLoadCounters());
    }

    class LoadChannel {
        class TabletsChannel {
            long indexId;
            List<PTabletWithPartition> tablets;

            TabletsChannel(long indexId) {
                this.indexId = indexId;
            }

            void open(PTabletWriterOpenRequest request) {
                tablets = request.tablets;
            }

            void cancel() {
            }

            void buildAndCommitRowset(long txnId, Tablet tablet) throws UserException {
                Rowset rowset = new Rowset(txnId, genRowsetId());
                txnManager.commit(txnId, tablet.partitionId, tablet, rowset);
            }

            void close(PTabletWriterAddChunkRequest request, PTabletWriterAddBatchResult result) throws UserException {
                for (PTabletWithPartition tabletWithPartition : tablets) {
                    Tablet tablet = tabletManager.getTablet(tabletWithPartition.tabletId);
                    if (tablet == null) {
                        LOG.warn("tablet not found {}", tabletWithPartition.tabletId);
                        continue;
                    }
                    buildAndCommitRowset(txnId, tablet);
                    PTabletInfo info = new PTabletInfo();
                    info.tabletId = tablet.id;
                    info.schemaHash = tablet.schemaHash;
                    result.tabletVec.add(info);
                }
            }
        }

        PUniqueId id;
        long txnId;
        Map<Long, TabletsChannel> indexToTabletsChannel = new HashMap<>();

        LoadChannel(PUniqueId id) {
            this.id = id;
        }

        void open(PTabletWriterOpenRequest request, PTabletWriterOpenResult result) {
            this.txnId = request.txnId;
            if (indexToTabletsChannel.containsKey(request.indexId)) {
                return;
            }
            TabletsChannel tabletsChannel = indexToTabletsChannel.computeIfAbsent(request.indexId, k -> new TabletsChannel(k));
            tabletsChannel.open(request);
        }

        void cancel() {
            for (TabletsChannel tabletsChannel : indexToTabletsChannel.values()) {
                tabletsChannel.cancel();
            }
        }

        void close(PTabletWriterAddChunkRequest request, PTabletWriterAddBatchResult result) throws UserException {
            TabletsChannel tabletsChannel = indexToTabletsChannel.get(request.indexId);
            if (tabletsChannel == null) {
                result.status.statusCode = TStatusCode.INTERNAL_ERROR.getValue();
                result.status.errorMsgs.add("cannot find the tablets channel associated with the index id");
            } else {
                tabletsChannel.close(request, result);
            }
        }
    }

    Map<String, LoadChannel> loadChannels = new HashMap<>();

    synchronized PTabletWriterOpenResult tabletWriterOpen(PTabletWriterOpenRequest request) {
        PTabletWriterOpenResult result = new PTabletWriterOpenResult();
        result.status = new StatusPB();
        result.status.statusCode = 0;
        result.status.errorMsgs = new ArrayList<>();
        String loadIdString = String.format("%d-%d", request.id.hi, request.id.lo);
        LoadChannel loadChannel = loadChannels.computeIfAbsent(loadIdString, k -> new LoadChannel(request.id));
        loadChannel.open(request, result);
        return result;
    }

    synchronized PTabletWriterCancelResult tabletWriterCancel(PTabletWriterCancelRequest request) {
        PTabletWriterCancelResult result = new PTabletWriterCancelResult();
        String loadIdString = String.format("%d-%d", request.id.hi, request.id.lo);
        LoadChannel loadChannel = loadChannels.get(loadIdString);
        if (loadChannel != null) {
            loadChannel.cancel();
        }
        return result;
    }

    synchronized PTabletWriterAddBatchResult tabletWriterAddChunk(PTabletWriterAddChunkRequest request) {
        PTabletWriterAddBatchResult result = new PTabletWriterAddBatchResult();
        result.status = new StatusPB();
        result.status.statusCode = 0;
        result.status.errorMsgs = new ArrayList<>();
        result.tabletVec = new ArrayList<>();
        if (request.eos) {
            String loadIdString = String.format("%d-%d", request.id.hi, request.id.lo);
            LoadChannel channel = loadChannels.get(loadIdString);
            if (channel != null) {
                if (random.nextFloat() < writeFailureRate) {
                    channel.cancel();
                    loadChannels.remove(loadIdString);
                    result.status.statusCode = TStatusCode.INTERNAL_ERROR.getValue();
                    result.status.errorMsgs.add("inject error writeFailureRate:" + writeFailureRate);
                    LOG.info("inject write failure txn:{} backend:{}", request.txnId, be.getId());
                    return result;
                }
                try {
                    channel.close(request, result);
                } catch (UserException e) {
                    LOG.warn("error close load channel", e);
                    channel.cancel();
                    loadChannels.remove(loadIdString);
                    result.status.statusCode = TStatusCode.INTERNAL_ERROR.getValue();
                    result.status.errorMsgs.add(e.getMessage());
                }
            } else {
                result.status.statusCode = TStatusCode.INTERNAL_ERROR.getValue();
                result.status.errorMsgs.add("no associated load channel");
            }
        }
        return result;
    }
}
