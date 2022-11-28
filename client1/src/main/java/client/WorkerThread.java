package client;

import org.apache.commons.lang3.concurrent.EventCountCircuitBreaker;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @author srish
 * WorkerThread that implements Runnable defines the actions to be implemented by individual threads.
 *
 */

public class WorkerThread implements Runnable {
    private final CountDownLatch latch;
    public String serverEnd;
    protected EventBuffer eventBuffer;
    public boolean isFirstPhase;
    private CloseableHttpClient httpClient;
    private int failed = 0; //keeps track of failed attempts
    EventCountCircuitBreaker breaker = new EventCountCircuitBreaker(10000, 15, TimeUnit.SECONDS,
            8000);


    /**
     * Constructor for WorkerThread class that takes in all arguments that needs to be passed to the threads
     * @param latch CountDown latch with thread count so the main thread waits until all threads complete execution
     * @param serverEnd Address of the remote server
     * @param eventBuffer  Queue containing events and payloads generated by the producer
     * @param isFirstPhase Indicates if each thread is supposed to execute a fixed number of threads or not
     */
    public WorkerThread(CountDownLatch latch, String serverEnd, EventBuffer eventBuffer, boolean isFirstPhase) {
        this.latch = latch;
        this.serverEnd = serverEnd;
        this.eventBuffer = eventBuffer;
        this.isFirstPhase = isFirstPhase;
        this.httpClient = HttpClientBuilder.create().build(); //Creates a client for sending request

    }

    /**
     * Defines code that is implemented when a thread that implements
     * WorkerThread object is started(i.e.,thread.start())
     * Implements Client part 1 of the assignment
     */
    public void run() {
        int successCount = 0;
        if (isFirstPhase) {
            //Each thread implements 1000 requests and terminates as long as consecutive failed attempts is less than 5
            for (String event = eventBuffer.retrieveEvent(); !event.equals("FIN"); event = eventBuffer.retrieveEvent()) {
                if (successCount < 1000 && failed < 5 && breaker.incrementAndCheckState()){
                    //System.out.println("closed---");
                    successCount = sendRequest(successCount, event);  //sends request
                } else {
                    //if requests sent exceeds 1000 or failed attempts exceeds 5, the event read from queue is putEvent back for future use by other threads
                    //System.out.println("The circuit is open!");
                    eventBuffer.putEvent(event);
                }
            }
            latch.countDown();
            //At the end of thread execution, successful requests and failed request count is updated
            Driver.SUCCESSFUL_REQUESTS.getAndAdd(successCount);
            Driver.FAILED_REQUESTS.getAndAdd(failed);
        } else {
            for (String event = eventBuffer.retrieveEvent(); !event.equals("FIN"); event = eventBuffer.retrieveEvent()) {
                if (failed < 5 && breaker.incrementAndCheckState()) {
                    //System.out.println("closed---");
                    successCount = sendRequest(successCount, event);//sends request
                } else {
                    //if requests sent exceeds 1000 or failed attempts exceeds 5, the event read from queue is putEvent back for future use by other threads
                    //System.out.println("The circuit is open!");
                    eventBuffer.putEvent(event);
                    eventBuffer.putPayload(eventBuffer.retrievePayload());
                }
            }
            latch.countDown();
            Driver.SUCCESSFUL_REQUESTS.getAndAdd(successCount);
            Driver.FAILED_REQUESTS.getAndAdd(failed);
        }
    }

    /**
     * sends request to specified server address with event details specified as path parameters and json body
     * @param successCount Number of requests successfully sent
     * @param event String containing path parameters describing the event
     * @return updated number of successful requests sent
     */
    private int sendRequest(int successCount, String event) {
        String url = this.serverEnd + event;    // generating url to send POST request to
        //creating an entity holding json body
        StringEntity entity = new StringEntity(eventBuffer.retrievePayload(), ContentType.APPLICATION_FORM_URLENCODED);
        HttpPost request = new HttpPost(url);
        request.setEntity(entity); //including entity with the POSt request
        try {
            CloseableHttpResponse response = httpClient.execute(request); //executing post request
            String message = EntityUtils.toString(response.getEntity());    //consuming the response sent
            int status = response.getStatusLine().getStatusCode();
            if (status != 200) {
                // Request failed
                failed++;
                System.out.println(message);
            } else {
                //Request successful
                successCount++;
                failed = 0;     //reset failure counter
            }
            response.close();   //close response stream to send new request
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
        return successCount;    //updated number of successful requests sent
    }
}