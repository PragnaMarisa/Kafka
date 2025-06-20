import { Kafka } from "npm:kafkajs";

const kafka = new Kafka({
  clientId: "deno-producer",
  brokers: ["localhost:9092"],
});

const producer = kafka.producer();

let counter = 0;

const startProducing = async () => {
  await producer.connect();

  setInterval(async () => {
    const key = `key-${counter}`;
    const value = `message-${counter}`;
    try {
      await producer.send({
        topic: "my-topic",
        messages: [{ key, value }],
      });
      console.log(`Produced: ${key}, ${value}`);
      counter++;
    } catch (err) {
      console.error("Error producing message:", err);
    }
  }, 2000); // every 2 seconds
};

startProducing().catch(console.error);
