// consumerB.ts
import { Kafka } from "npm:kafkajs";

const kafka = new Kafka({
  clientId: "deno-consumer-B",
  brokers: ["localhost:9092"],
});

const consumer = kafka.consumer({ groupId: "group-B" });

const consume = async () => {
  await consumer.connect();
  await consumer.subscribe({ topic: "my-topic", fromBeginning: true });

  await consumer.run({
    eachMessage: async ({ message }) => {
      console.log(`[Consumer B] ${message.key?.toString()}: ${message.value?.toString()}`);
    },
  });
};

consume().catch(console.error);
