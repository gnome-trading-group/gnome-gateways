package group.gnometrading.gateways;

import group.gnometrading.objects.MessageHeaderDecoder;
import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import org.agrona.concurrent.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;

public class Main {
    public static void main(String[] args) {
        final short BEGIN_STRING_SHORT = '8' << 8 | '=';
        final String channelId = "aeron:ipc";
        final String message = "This is the message to transport.";
        final int NUM_MESSAGES = 1_000_000;
        final int NUM_SUBS = 10;

        final ShutdownSignalBarrier barrier = new ShutdownSignalBarrier();

        //Step 1: Construct Media Driver, cleaning up media driver folder on start/stop
        final MediaDriver.Context mediaDriverCtx = new MediaDriver.Context()
                .dirDeleteOnStart(true);
//                .dirDeleteOnShutdown(true)
//                .aeronDirectoryName("/dev/shm/aeron");
        System.out.println(mediaDriverCtx.aeronDirectoryName());

        MediaDriver driver = MediaDriver.launch(mediaDriverCtx);
        Aeron aeron = Aeron.connect();

        final var subscriptions = new ArrayList<AgentRunner>();
        for (int i = 0; i < NUM_SUBS; i++) {
            final var subscription = aeron.addSubscription(channelId, 10);
            final var agent = new SubscriptionAgent(subscription, NUM_MESSAGES, "Sub" + i);
            subscriptions.add(
                    new AgentRunner(new YieldingIdleStrategy(), Throwable::printStackTrace, null, agent)
            );
        }
        Publication publication = aeron.addPublication(channelId, 10);

        PublicationAgent publicationAgent = new PublicationAgent(publication, NUM_MESSAGES);

        final var publicationAgentRunner = new AgentRunner(new YieldingIdleStrategy(), Throwable::printStackTrace, null, publicationAgent);

        System.out.println("Running!");

        AgentRunner.startOnThread(publicationAgentRunner);

        long startTime = System.currentTimeMillis();
        for (int i = 0; i < subscriptions.size() / 2; i++) {
            AgentRunner.startOnThread(subscriptions.get(i));
        }

        try {
            Thread.sleep(10 * 1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        for (int i = subscriptions.size() / 2; i < subscriptions.size(); i++) {
            AgentRunner.startOnThread(subscriptions.get(i));
        }

        barrier.await();
        long endTime = System.currentTimeMillis();
        System.out.printf("Process time: %s", endTime - startTime);

        //close the resources
        publicationAgentRunner.close();
        for (var runner : subscriptions) {
            runner.close();
        }

        aeron.close();
        driver.close();
    }

    private static class SubscriptionAgent implements Agent {

        private final Subscription subscription;
        private final ShutdownSignalBarrier shutdownSignalBarrier;
        private final int numberOfMessages;
        private final String roleName;
        private boolean logged = false;

        public SubscriptionAgent(Subscription subscription, int numberOfMessages, String roleName) {
            this.subscription = subscription;
            this.shutdownSignalBarrier = new ShutdownSignalBarrier();
            this.numberOfMessages = numberOfMessages;
            this.roleName = roleName;
        }

        @Override
        public int doWork() throws Exception {
            subscription.poll((buffer, offset, length, header) -> {
                final int lastValue = buffer.getInt(offset);
                if (!logged) {
                    this.logged = true;
                    System.out.println(this.roleName + " first value " + lastValue);
                }
                if (lastValue == this.numberOfMessages) {
                    System.out.println(this.roleName + " received " + lastValue);
                    shutdownSignalBarrier.signal();
                }
            }, 1);
            return 0;
        }

        @Override
        public String roleName() {
            return roleName;
        }
    }

    private static class PublicationAgent implements Agent {

        private final Publication publication;
        private final int numberOfMessages;
        private final UnsafeBuffer unsafeBuffer;
        private int currentMessageCount;

        public PublicationAgent(final Publication publication, final int numberOfMessages) {
            this.publication = publication;
            this.numberOfMessages = numberOfMessages;
            this.unsafeBuffer = new UnsafeBuffer(ByteBuffer.allocate(4));
            this.currentMessageCount = 1;
            this.unsafeBuffer.putInt(0, this.currentMessageCount);
        }

        @Override
        public int doWork() throws Exception {
            if (this.currentMessageCount > this.numberOfMessages) {
                return 0;
            }

            if (publication.isConnected()) {
                long offer = publication.offer(unsafeBuffer);
                if (offer > 0) {
//                    System.out.println("put into " + this.currentMessageCount);
                    this.currentMessageCount += 1;
                    this.unsafeBuffer.putInt(0, this.currentMessageCount);
                } else {
//                    System.out.println("Last insert: " + this.currentMessageCount);
//                    System.out.println("offer " + offer);
                }
            }

            return 0;
        }

        @Override
        public String roleName() {
            return "sender";
        }
    }
}