const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";
const CHARLIE_UID = "charlie_789";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function serverTime(ctx) {
  const { serverTimestamp } = require("firebase/firestore");
  return serverTimestamp();
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read or list markets, price_records, or shopping_lists", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("markets").get());
  await assertFails(unauthDb.collection("price_records").get());
  await assertFails(unauthDb.collection("shopping_lists").get());
  await assertFails(unauthDb.collection("list_invites").doc("FEIRA-1234").get());
});

test("Authenticated user can create and query own market and price_record, but not another user's", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = new Date();

  await assertSucceeds(
    aliceDb.collection("markets").doc("mkt_1").set({
      userId: ALICE_UID,
      name: "Assai Atacadista",
      neighborhood: "Centro",
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    aliceDb.collection("price_records").doc("rec_1").set({
      userId: ALICE_UID,
      productName: "Arroz Tipo 1",
      normalizedName: "arroz tipo 1",
      brand: "Camil",
      category: "Mercearia",
      unitLabel: "5 kg",
      priceCents: 2690,
      marketId: "mkt_1",
      marketName: "Assai Atacadista",
      isPromo: true,
      recordedAtMillis: Date.now(),
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(aliceDb.collection("markets").where("userId", "==", ALICE_UID).get());
  await assertSucceeds(aliceDb.collection("price_records").where("userId", "==", ALICE_UID).get());

  // Unfiltered list fails
  await assertFails(aliceDb.collection("price_records").get());
  // Cross-user read fails
  await assertFails(bobDb.collection("markets").doc("mkt_1").get());
  await assertFails(bobDb.collection("price_records").doc("rec_1").get());
  // Spoofed userId fails
  await assertFails(
    bobDb.collection("price_records").doc("rec_spoof").set({
      userId: ALICE_UID,
      productName: "Feijao",
      normalizedName: "feijao",
      brand: "",
      category: "Mercearia",
      unitLabel: "1 kg",
      priceCents: 790,
      marketId: "mkt_1",
      marketName: "Assai Atacadista",
      isPromo: false,
      recordedAtMillis: Date.now(),
      createdAt: now,
      updatedAt: now,
    })
  );
  // Shadow field injection fails
  await assertFails(
    aliceDb.collection("markets").doc("mkt_shadow").set({
      userId: ALICE_UID,
      name: "Mercado X",
      neighborhood: "",
      isAdmin: true,
      createdAt: now,
      updatedAt: now,
    })
  );
});

test("Family shopping list invite join, item CRUD, and non-member rejection", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  const now = new Date();

  await assertSucceeds(
    aliceDb.collection("shopping_lists").doc("list_1").set({
      ownerId: ALICE_UID,
      memberUids: [ALICE_UID],
      title: "Compra da Semana",
      inviteCode: "FEIRA-4829",
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    aliceDb.collection("list_invites").doc("FEIRA-4829").set({
      listId: "list_1",
      listTitle: "Compra da Semana",
      ownerId: ALICE_UID,
      createdAt: now,
    })
  );

  // Invite enumeration (list) is strictly forbidden
  await assertFails(bobDb.collection("list_invites").get());

  // Bob can read exact invite doc
  await assertSucceeds(bobDb.collection("list_invites").doc("FEIRA-4829").get());

  // Alice adds an item
  await assertSucceeds(
    aliceDb.collection("shopping_lists").doc("list_1").collection("items").doc("item_1").set({
      listId: "list_1",
      productName: "Leite Integral",
      normalizedName: "leite integral",
      quantity: 2,
      unitLabel: "1 L",
      checked: false,
      addedByUid: ALICE_UID,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Bob cannot read list or items before joining
  await assertFails(bobDb.collection("shopping_lists").doc("list_1").get());
  await assertFails(bobDb.collection("shopping_lists").doc("list_1").collection("items").get());

  // Bob joins list_1 via inviteCode
  await assertSucceeds(
    bobDb.collection("shopping_lists").doc("list_1").update({
      memberUids: [ALICE_UID, BOB_UID],
      updatedAt: new Date(),
    })
  );

  // Bob can now read list, list items, and check off item_1
  await assertSucceeds(
    bobDb.collection("shopping_lists").where("memberUids", "array-contains", BOB_UID).get()
  );
  await assertSucceeds(bobDb.collection("shopping_lists").doc("list_1").collection("items").get());
  await assertSucceeds(
    bobDb.collection("shopping_lists").doc("list_1").collection("items").doc("item_1").update({
      checked: true,
      updatedAt: new Date(),
    })
  );

  // Charlie (non-member) still rejected
  await assertFails(charlieDb.collection("shopping_lists").doc("list_1").collection("items").get());
});

test("Atomic batch create of shopping_lists and list_invites succeeds", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date();
  const batch = aliceDb.batch();
  const listRef = aliceDb.collection("shopping_lists").doc("batch_list_1");
  const inviteRef = aliceDb.collection("list_invites").doc("FJ-ABC123");

  batch.set(listRef, {
    ownerId: ALICE_UID,
    memberUids: [ALICE_UID],
    title: "Feira da Familia",
    inviteCode: "FJ-ABC123",
    createdAt: now,
    updatedAt: now,
  });
  batch.set(inviteRef, {
    listId: "batch_list_1",
    listTitle: "Feira da Familia",
    ownerId: ALICE_UID,
    createdAt: now,
  });

  await assertSucceeds(batch.commit());
});
