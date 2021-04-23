package com.pawelgorny.wifsolver;

import org.bitcoinj.core.Base58;
import org.bitcoinj.core.DumpedPrivateKey;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.LegacyAddress;

import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

class WorkerEnd extends Worker {

    private static boolean found = false;
    private final Configuration configuration;
    private final int THREADS_MIN = 1;
    private int THREADS = THREADS_MIN;

    public WorkerEnd(Configuration configuration) {
        super(configuration);
        this.configuration = configuration;
    }

    private static boolean arrFinished(int[] array, int arrLimit) {
        return !(array[0] < arrLimit);
    }

    private static void increment58(int[] array) {
        int i = array.length - 1;
        do {
            array[i] = (array[i] + 1) % 58;
        } while (array[i--] == 0 && i >= 0);
    }

    protected void run() throws InterruptedException {
        String wif = configuration.getWif();
        String address = configuration.getTargetAddress();

        int len = 51;
        if (wif.startsWith("L")||wif.startsWith("K")){
            len = Configuration.COMPRESSED_WIF_LENGTH;
        }
        int missing = len - wif.length();
        if (missing <= 0) {
            System.out.println("nothing to do?");
            System.exit(0);
        }
        if (missing <= Configuration.getChecksumChars()) {
            System.out.println("Missing less than " + Configuration.getChecksumChars() + " last characters, quick check launched");
            checksumCheck(missing, wif, len, null, "");
        } else {
            if (missing - Configuration.getChecksumChars() > 3) {
                setThreads();
                System.out.println("Using " + THREADS + " threads");
            }
            final CountDownLatch latch = new CountDownLatch(THREADS);
            ExecutorService executorService = Executors.newFixedThreadPool(THREADS);
            final int step = 58 / THREADS;
            final String[] lastTested = new String[THREADS];
            for (int t = 0; t < THREADS; t++) {
                lastTested[t] = "";
                final boolean reporter = t == 0;
                final int tNr = t;
                final int expectedLength = len;
                executorService.submit(() -> {
                    try {
                        int[] arr = new int[missing - Configuration.getChecksumChars()];
                        arr[0] = tNr * step;
                        final int arrLimit = Math.min(58, (tNr + 1) * step);
                        StringBuilder stringBuilderThread = new StringBuilder(expectedLength);
                        long start = System.currentTimeMillis();
                        while (!found && !arrFinished(arr, arrLimit)) {
                            if (reporter && (System.currentTimeMillis() - start > Configuration.getStatusPeriod())) {
                                System.out.println("PING! " + (new Date()) + " " + Arrays.toString(lastTested));
                                start = System.currentTimeMillis();
                            }
                            stringBuilderThread.setLength(0);
                            stringBuilderThread.append(wif);
                            for (int anArr : arr) {
                                stringBuilderThread.append(Base58.ALPHABET[anArr]);
                            }
                            lastTested[tNr] = checksumCheck(Configuration.getChecksumChars(), stringBuilderThread.toString(), expectedLength, address, lastTested[tNr]);
                            if (found) {
                                break;
                            }
                            increment58(arr);
                        }
                        latch.countDown();
                    } catch (Exception e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            latch.await();
            executorService.shutdown();
        }
        if (!found) {
            System.out.println("WIF not found");
        }
    }

    private String checksumCheck(int missing, String wif, int len, final String address, String lastTested) {
        StringBuilder sb = new StringBuilder(len);
        sb.append(wif);
        for (int m = 0; m < missing; m++) {
            sb.append('1');
        }
        byte[] bytes = Base58.decode(sb.toString());
        bytes = Arrays.copyOfRange(bytes, 1, bytes.length - 4);
        if (len == Configuration.COMPRESSED_WIF_LENGTH) {
            bytes[32] = 1;
        }
        String encoded = Base58.encodeChecked(128, bytes);
        if (lastTested.equals(encoded)) {
            return lastTested;
        }
        if (encoded.startsWith(configuration.getWif())) {
            ECKey ecKey = DumpedPrivateKey.fromBase58(Configuration.getNetworkParameters(), encoded).getKey();
            String foundAddress = len == Configuration.COMPRESSED_WIF_LENGTH ? LegacyAddress.fromKey(Configuration.getNetworkParameters(), ecKey).toString()
                    : LegacyAddress.fromKey(Configuration.getNetworkParameters(), ecKey.decompress()).toString();
            if (address != null) {
                if (foundAddress.equals(address)) {
                    found = true;
                    super.addResult(encoded + " -> " + foundAddress);
                    System.out.println(encoded + " -> " + foundAddress);
                }
            } else {
                System.out.println(encoded + " -> " + foundAddress);
                super.addResult(encoded + " -> " + foundAddress);
            }
        }
        return encoded;
    }

    private void setThreads() {
        int procs = Runtime.getRuntime().availableProcessors();
        if (procs < 1) {
            procs = THREADS_MIN;
        }
        THREADS = Math.min(procs, 29);
    }
}
