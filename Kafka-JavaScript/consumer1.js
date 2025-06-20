// consumerA.ts
import { Kafka } from "npm:kafkajs";

const kafka = new Kafka({
  clientId: "deno-consumer-A",
  brokers: ["localhost:9092"],
});

const consumer = kafka.consumer({ groupId: "group-A" });

const consume = async () => {
  await consumer.connect();
  await consumer.subscribe({ topic: "my-topic", fromBeginning: true });

  await consumer.run({
    eachMessage: async ({ message }) => {
      console.log(`[Consumer A] ${message.key?.toString()}: ${message.value?.toString()}`);
    },
  });
};

consume().catch(console.error);
