package org.globsframework.remote.shared.impl;

import org.globsframework.json.GSonUtils;
import org.globsframework.metamodel.GlobModel;
import org.globsframework.metamodel.GlobType;
import org.globsframework.model.ChangeSet;
import org.globsframework.model.ChangeSetListener;
import org.globsframework.model.Glob;
import org.globsframework.model.GlobRepository;
import org.globsframework.model.repository.DefaultGlobIdGenerator;
import org.globsframework.model.repository.DefaultGlobRepository;
import org.globsframework.model.utils.GlobFunctor;
import org.globsframework.remote.shared.AddressAccessor;
import org.globsframework.remote.shared.SharedDataService;
import org.globsframework.utils.NanoChrono;
import org.globsframework.utils.ReusableByteArrayOutputStream;
import org.globsframework.utils.Utils;
import org.globsframework.utils.collections.Pair;
import org.globsframework.utils.serialization.SerializedInput;
import org.globsframework.utils.serialization.SerializedInputOutputFactory;
import org.globsframework.utils.serialization.SerializedOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ClientSharedData implements SharedDataService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientSharedData.class);
    private static final int MAX_MSG_TO_READ = Integer.getInteger("org.globsframework.remote.shared.ClientSharedData.max.message.per.round", 10);
    private static final int LOG_PROPAGATE = Integer.getInteger("org.globsframework.remote.shared.ClientSharedData.propagate.timeout", 1000);
    private final Thread thread;
    private final AddressAccessor addressAccessor;
    private final OnStop onStop;
    private final Object initializationLock = new Object();
    private final Object listenersLock = new Object();
    private final Object remoteRwLock = new Object();
    private Selector selector;
    private DefaultGlobRepository repository;
    private ReadWriteLock readWriteLock = new ReentrantReadWriteLock(true);
    private volatile SharedDataEventListener[] sharedDataEventListeners;
    private volatile int connectionId; // volatile for test should be access only from read/write
    private int changeId;
    private SelectionKey selectionKey;
    private SharedModelType sharedModelType;
    private RemoteRWState remoteRWState;
    private volatile boolean stop = false;
    private boolean connected = false;
    private boolean initialized = false;
    private ReusableByteArrayOutputStream sendOutputStream;

    public ClientSharedData(GlobModel globModel, final AddressAccessor addressAccessor, OnStop onStop, final String debugInfo, SharedDataEventListener... listeners) {
        this.addressAccessor = addressAccessor;
        this.onStop = onStop;
        repository = new DefaultGlobRepository(new DefaultGlobIdGenerator());
        sharedModelType = new SharedModelType(globModel);

        sharedDataEventListeners = listeners == null ? new SharedDataEventListener[0] : Arrays.copyOf(listeners, listeners.length);

        thread = new ClientThread();
        thread.setName("ClientSharedData : " + debugInfo + "(" + thread.getId() + ")");
        thread.setDaemon(true);

        // we increase priority so the shared memory is updated in priority to other io (rpc call)
        thread.setPriority(Thread.MAX_PRIORITY - 1);
        thread.start();
    }

    private void resetIdAndUpdateChangeSet() {
        Lock lock = readWriteLock.writeLock();
        lock.lock();
        try {
            repository.startChangeSetWithoutChange();
            sharedModelType.deleteOther(repository, connectionId);
        } finally {
            repository.completeChangeSetWithoutTriggers();
            lock.unlock();
        }
    }

    public int getId() {
        return connectionId;
    }

    public <T extends SharedData> T read(T sharedData) {
        if (stop) {
            throw new RuntimeException("Client stopped.");
        }
        Lock lock = readWriteLock.readLock();
        lock.lock();
        try {
            sharedData.data(repository);
        }catch (Exception exception){
            LOGGER.error("In read client code", exception);
        } finally {
            lock.unlock();
        }
        return sharedData;
    }

    public <T extends SharedData> T write(T sharedData) {
        if (stop) {
            throw new RuntimeException("Client stopped.");
        }
        Lock lock = readWriteLock.writeLock();
        lock.lock();
        ChangeSetRetriever listener = new ChangeSetRetriever();
        repository.addChangeListener(listener);
        try {
            repository.startChangeSet();
            sharedData.data(repository);
        } catch (Exception exception) {
            LOGGER.error("error write in client code", exception);
            throw exception;
        } finally {
            try {
                repository.completeChangeSet();
            } finally {
                repository.removeChangeListener(listener);
                ChangeSet changeSet = listener.changeSet;
                if (changeSet != null) {

                    // extract new Type from changeSet and add them to globModel and
                    GlobType[] changedTypes = changeSet.getChangedTypes();
                    List<GlobType> typeToSend = new ArrayList<>();
                    for (GlobType changedType : changedTypes) {
                        if (sharedModelType.addType(changedType)) {
                            typeToSend.add(changedType);
                        }
                    }
                    synchronized (remoteRwLock) {
                        ++changeId;
                        if (connected && remoteRWState != null) {
                            sendChangeSet(remoteRWState, changeSet, typeToSend);
                            typeToSend.clear();
                        }
                    }
                }
                lock.unlock();
                if (changeSet != null) {
                    propagate(changeSet);
                }
            }
        }

        return sharedData;
    }

    private void propagate(ChangeSet changeSet) {
        for (SharedDataEventListener sharedDataEventListener : sharedDataEventListeners()) {
            NanoChrono chrono = NanoChrono.start();
            sharedDataEventListener.event(changeSet);
            int elapsedTime = (int) chrono.getElapsedTimeInMS();
            if (LOGGER.isDebugEnabled() || elapsedTime > LOG_PROPAGATE) {
                LOGGER.info(elapsedTime + " ms to propagate changeSet some where in " + sharedDataEventListener);
            }
        }
    }

    private void reset() {
        for (SharedDataEventListener sharedDataEventListener : sharedDataEventListeners()) {
            NanoChrono chrono = NanoChrono.start();
            sharedDataEventListener.reset();
            int elapsedTime = (int) chrono.getElapsedTimeInMS();
            if (LOGGER.isDebugEnabled() || elapsedTime > LOG_PROPAGATE) {
                LOGGER.info(elapsedTime + "ms to propagate reset for " + sharedDataEventListener);
            }
        }
    }

    public void listen(SharedDataEventListener sharedDataEventListener) {
        synchronized (listenersLock) {
            SharedDataEventListener[] tmp = this.sharedDataEventListeners;
            SharedDataEventListener[] newVectors = Arrays.copyOf(tmp, tmp.length + 1);
            newVectors[newVectors.length - 1] = sharedDataEventListener;
            this.sharedDataEventListeners = newVectors;
        }
    }

    public void remove(SharedDataEventListener sharedDataEventListener) {
        synchronized (listenersLock) {
            int occ = 0;
            for (SharedDataEventListener dataEventListener : sharedDataEventListeners) {
                if (dataEventListener == sharedDataEventListener) {
                    occ++;
                }
            }
            if (occ == 0) {
                LOGGER.info("Listener removed twice " + sharedDataEventListener);
                return;
            }
            SharedDataEventListener[] newListeners = new SharedDataEventListener[sharedDataEventListeners.length - occ];
            int i = -1;
            for (SharedDataEventListener dataEventListener : sharedDataEventListeners) {
                if (dataEventListener != sharedDataEventListener) {
                    newListeners[++i] = dataEventListener;
                }
            }
            sharedDataEventListeners = newListeners;
        }
    }

    private SharedDataEventListener[] sharedDataEventListeners() {
        return sharedDataEventListeners;
    }

    public void stop() {
        LOGGER.info("Stop requested on " + (remoteRWState != null ? remoteRWState.getClientId() : " not connected."));
        stop = true;
        try {
            synchronized (remoteRwLock) {
                if (remoteRWState != null) {
                    remoteRWState.cancel();
                }
                selector.close();
            }
        } catch (Exception e) {
        }
        try {
            thread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException("in stop", e);
        }
        onStop.stopped();
    }

    public boolean waitForInitialization(int timeoutInMilliS) {
        long endAt = System.currentTimeMillis() + timeoutInMilliS;
        long timeout;
        synchronized (initializationLock) {
            while (!initialized && (timeout = endAt - System.currentTimeMillis()) > 0) {
                Utils.doWait(initializationLock, timeout);
            }
            return initialized;
        }
    }

    public void run() {
        try {

            Pair<String, Integer> hostAndPort = addressAccessor.getHostAndPort();
            if (hostAndPort == null) {
                return;
            }
            LOGGER.info("Connect to " + hostAndPort.getFirst() + ":" + hostAndPort.getSecond());
            SocketChannel socketChannel = null;
            try {
                socketChannel = SocketChannel.open();
                socketChannel.connect(new InetSocketAddress(hostAndPort.getFirst(), hostAndPort.getSecond()));
                socketChannel.configureBlocking(false);
                socketChannel.socket().setTcpNoDelay(DefaultServerSharedData.TCP_NO_DELAY);
                selectionKey = socketChannel.register(selector, SelectionKey.OP_READ);
            } catch (Exception e) {
                LOGGER.error("Fail to connect to " + hostAndPort.getFirst() + ":" + hostAndPort.getSecond() + ". Cause by : " + e.getMessage() + " (see console for more info)", e);
                if (socketChannel != null) {
                    socketChannel.close();
                }
                throw e;
            }
            remoteRWState = new RemoteRWState(socketChannel);
            remoteRWState.setSelectionKey(selectionKey);

            while (!stop) {
                int select = selector.select(1000);
                if (select > 0) {
                    Set<SelectionKey> selectionKeys = selector.selectedKeys();
                    if (selectionKeys.remove(selectionKey)) {
                        if (selectionKey.isValid() && selectionKey.isReadable()) {
                            remoteRWState.read(new RemoteRWState.MsgCallback() {
                                public void msg(byte[] msg, int msgSize) {
                                    SerializedInput serializedInput = SerializedInputOutputFactory.initCompressedAndIntern(msg, msgSize);
                                    Lock lock = readWriteLock.writeLock();
                                    try {
                                        lock.lock();
                                        int id = serializedInput.readNotNullInt();
                                        if (id == 0) {
                                            connectionId = serializedInput.readNotNullInt();
                                            LOGGER.info("Client connected " + connectionId);
                                            remoteRWState.setClientId(connectionId);
                                            repository.startChangeSetWithoutChange();
                                            sharedModelType.updateRepoWith(repository, connectionId);
                                            repository.completeChangeSetWithoutTriggers();
                                            synchronized (remoteRwLock) {
                                                sendRepository();
                                                connected = true;
                                            }
                                            lock.unlock();
                                            lock = null;
                                        } else if (id < 0) {
                                            int remoteConnectionId = serializedInput.readNotNullInt(); //connectionId

                                            //read new Types
                                            //add them to SharedModelType

                                            int typesToRead = serializedInput.readNotNullInt();
                                            while (typesToRead > 0) {
                                                GlobType globType = GSonUtils.decodeGlobType(serializedInput.readUtf8String(), sharedModelType.getGlobModel()::findType, true);
                                                sharedModelType.addType(globType);
                                                typesToRead--;
                                            }

                                            int len = serializedInput.readNotNullInt();
                                            if (LOGGER.isInfoEnabled()) {
                                                LOGGER.info("Received repo " + remoteConnectionId + ":" + id + " with " + len + " globs.");
                                            }
                                            repository.startChangeSetWithoutChange();
                                            while (len > 0) {
                                                Glob glob = serializedInput.readGlob(sharedModelType.getGlobModel());
                                                if (LOGGER.isDebugEnabled()) {
                                                    LOGGER.debug("Receive : " + GSonUtils.encode(glob, true));
                                                }
                                                repository.add(glob);
                                                --len;
                                            }
                                            repository.completeChangeSetWithoutTriggers();
                                            lock.unlock();
                                            lock = null;
                                            reset();

                                            if (!initialized) {
                                                synchronized (initializationLock) {
                                                    initialized = true;
                                                    initializationLock.notifyAll();
                                                }
                                            }
                                        } else {
                                            int remoteConnectionId = serializedInput.readNotNullInt(); //connectionId

                                            //read new Types
                                            //add them to SharedModelType
                                            int typesToRead = serializedInput.readNotNullInt();
                                            while (typesToRead > 0) {
                                                GlobType globType = GSonUtils.decodeGlobType(serializedInput.readUtf8String(), sharedModelType.getGlobModel()::findType, true);
                                                if (LOGGER.isDebugEnabled()) {
                                                    LOGGER.debug("Receive type : " + GSonUtils.encodeGlobType(globType));
                                                }
                                                sharedModelType.addType(globType);
                                                typesToRead--;
                                            }


                                            ChangeSet changeSet = serializedInput.readChangeSet(sharedModelType.getGlobModel());
                                            repository.startChangeSetWithoutChange();
                                            repository.apply(changeSet);
                                            repository.completeChangeSetWithoutTriggers();
                                            if (LOGGER.isDebugEnabled()) {
                                                if (LOGGER.isTraceEnabled()) {
                                                    LOGGER.trace("Received  changeSet " + remoteConnectionId + ":" + id + changeSet.toString());
                                                } else {
                                                    LOGGER.debug("Received changeSet " + remoteConnectionId + ":" + id);
                                                }
                                            }
                                            // lock is released here because, we don't know if the listener will take a read or a read/write lock
                                            // It is dangerous because the changeSet can be associated to a globRepository that can change.
                                            lock.unlock();
                                            lock = null;
                                            propagate(changeSet);
                                        }
                                    } catch (RuntimeException e) {
                                        //TODO check why/what
                                        LOGGER.error("Unexpected error " + e.getMessage(), e);
                                        throw e;
                                    } finally {
                                        if (lock != null) {
                                            try {
                                                repository.completeChangeSetWithoutTriggers();
                                            } finally {
                                                lock.unlock();
                                            }
                                        }
                                    }
                                }
                            }, MAX_MSG_TO_READ);
                        }
                        synchronized (remoteRwLock) {
                            if (selectionKey.isValid()) {
                                if (selectionKey.isWritable()) {
                                    remoteRWState.writeNext();
                                }
                            } else {
                                LOGGER.warn("Connection lost to shared data server.");
                                remoteRWState.cancel();
                                connected = false;
                                remoteRWState = null;

                            }
                        }
                    } else {
                        throw new RuntimeException("bug");
                    }
                }
                synchronized (remoteRwLock) {
                    if (remoteRWState == null || remoteRWState.isClosed()) {
                        LOGGER.warn("Connection lost to shared data server.");
                        selectionKey.cancel();
                        connected = false;
                        remoteRWState = null;
                        return;
                    }
                }
            }
        } catch (Exception e) {
            synchronized (remoteRwLock) {
                if (remoteRWState != null) {
                    LOGGER.warn("Connection lost to shared data server with io exception", e);
                    remoteRWState.cancel();
                } else {
                    LOGGER.info("Connection lost to shared data server with io exception " + e.getMessage());
                }
                connected = false;
                remoteRWState = null;
            }
        }
    }

    private void sendRepository() {
        if (remoteRWState == null) {
            return;
        }
        int globCount = repository.size();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Send " + globCount + " globs to server");
        }
        if (globCount > 0) {

            ReusableByteArrayOutputStream outputStream = new ReusableByteArrayOutputStream();
            outputStream.setTo(4);
            final SerializedOutput serializedOutput = SerializedInputOutputFactory.initCompressed(outputStream);

            serializedOutput.write(-changeId);
            serializedOutput.write(connectionId);

            //send all GlobTypes.
            GlobModel globModel = sharedModelType.getGlobModel();
            serializedOutput.write(globModel.getAll().size());
            for (GlobType globType : globModel) {
                String s = GSonUtils.encodeGlobType(globType);
                serializedOutput.writeUtf8String(s);
            }

            serializedOutput.write(globCount);
            repository.safeApply(new GlobFunctor() {
                public void run(Glob glob, GlobRepository repository) throws Exception {
                    serializedOutput.writeGlob(glob);
                }
            });
            int size = outputStream.size();
            outputStream.reset();
            serializedOutput.write(getSizeWithKey(size - 4));
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Send changeSet " + connectionId + ":" + (-changeId));
            }
            try {
                if (!remoteRWState.write(outputStream.getBuffer(), size)) {
                    selector.wakeup();
                }
            } catch (IOException e) {
                throw new RuntimeException("Should not happen");
            }
        }
    }

    private void sendChangeSet(RemoteRWState remoteRWState, ChangeSet changeSet, List<GlobType> typeToSend) {
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Send changeSet " + connectionId + ":" + changeId + changeSet.toString());
        }
        if (sendOutputStream == null) {
            sendOutputStream = new ReusableByteArrayOutputStream();
        }
        sendOutputStream.setTo(4);
        SerializedOutput serializedOutput = SerializedInputOutputFactory.initCompressed(sendOutputStream);
        serializedOutput.write(changeId);
        serializedOutput.write(connectionId);

        //send new Types.

        serializedOutput.write(typeToSend.size());
        for (GlobType globType : typeToSend) {
            String s = GSonUtils.encodeGlobType(globType);
            serializedOutput.writeUtf8String(s);
        }

        serializedOutput.writeChangeSet(changeSet);
        int size = sendOutputStream.size();
        if (size > RemoteRWState.MAX_MSG_SIZE) {
            String message = "Message too big " + size + " : " + changeSet.toString();
            LOGGER.error(message);
            repository.startChangeSetWithoutChange();
            repository.apply(changeSet.reverse());
            repository.completeChangeSetWithoutTriggers();
            throw new RuntimeException(message);
        }
        sendOutputStream.reset();
        serializedOutput.write(getSizeWithKey(size - 4));
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Send changeSet " + connectionId + ":" + changeId);
        }
        try {
            if (!remoteRWState.write(sendOutputStream.getBuffer(), size)) {
                sendOutputStream = null;
                selector.wakeup();
            } else {
                sendOutputStream.reset();
            }
        } catch (IOException e) {
            String message = "Write fail will retry";
            if (LOGGER.isDebugEnabled()) {
                LOGGER.warn(message, e);
            } else {
                LOGGER.warn(message);
            }
            connected = false;
            try {
                selector.close();
            } catch (IOException e1) {
                throw new RuntimeException("can not stop selector ", e1);
            }
        }
    }

    public static int getSizeWithKey(int size) {
        int i = size | RemoteRWState.MARK;
        if ((i & RemoteRWState.MASK_FOR_SIZE) != size) {
            throw new RuntimeException("Too big repository size : " + size);
        }
        return i;
    }

    public interface OnStop {
        OnStop NULL = new OnStop() {
            public void stopped() {
            }
        };

        void stopped();
    }

    private static class ChangeSetRetriever implements ChangeSetListener {
        private ChangeSet changeSet;

        public void globsChanged(ChangeSet changeSet, GlobRepository repository) {
            this.changeSet = changeSet;
        }

        public void globsReset(GlobRepository repository, Set<GlobType> changedTypes) {
        }
    }

    private class ClientThread extends Thread {

        public void run() {
            try {
                while (!stop) {
                    try {
                        if (selector != null) {
                            selector.close();
                        }
                        selector = Selector.open();
                    } catch (IOException e) {
                        throw new RuntimeException("Fail to create selector", e);
                    }
                    ClientSharedData.this.run();
                    LOGGER.info("Connection closed");
                    resetIdAndUpdateChangeSet();
                    try {
                        if (!stop) {
                            LOGGER.info("try re-connect in 1s");
                            Thread.sleep(1000);
                        }
                    } catch (InterruptedException e) {
                        throw new RuntimeException("End in interrupt", e);
                    }
                }
                LOGGER.info("Client stopped");
            } catch (Exception e) {
                /*
                if (e.getCause() instanceof IOException) {
                    logger.info("Client end : " + e.getMessage());
                } else {
                    logger.error("Client end in error.", e);
                }
                */
                LOGGER.error("Client end in error.", e);
            }
        }
    }
}
