package me.jellysquid.mods.lithium.mixin.block.hopper;

import me.jellysquid.mods.lithium.common.entity.tracker.nearby.NearbyInventoryEntityMovementTracker;
import me.jellysquid.mods.lithium.common.entity.tracker.nearby.NearbyItemEntityMovementTracker;
import me.jellysquid.mods.lithium.common.hopper.HopperHelper;
import me.jellysquid.mods.lithium.common.hopper.LithiumInventory;
import me.jellysquid.mods.lithium.common.hopper.LithiumStackList;
import me.jellysquid.mods.lithium.common.hopper.UpdateReceiver;
import net.minecraft.block.BlockState;
import net.minecraft.block.HopperBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.entity.Hopper;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.vehicle.HopperMinecartEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SidedInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static net.minecraft.block.entity.HopperBlockEntity.getInputItemEntities;


@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin extends BlockEntity implements Hopper, UpdateReceiver, LithiumInventory {
    private static final Inventory NO_INVENTORY_BLOCK_PRESENT = new SimpleInventory(0);

    public HopperBlockEntityMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Shadow
    @Nullable
    private static native Inventory getInputInventory(World world, Hopper hopper);

    @Shadow
    private static native boolean insert(World world, BlockPos pos, BlockState state, Inventory inventory);

    @Shadow
    protected abstract boolean isDisabled();

    @Shadow
    private long lastTickTime;

    @Shadow
    protected abstract void setCooldown(int cooldown);

    @Shadow
    private static native boolean canExtract(Inventory inv, ItemStack stack, int slot, Direction facing);

    private long myLastInsertChangeCount, myLastExtractChangeCount, myLastCollectChangeCount;

    //these fields, together with the removedCount are storing the relevant data for deciding whether a cached inventory can be used again
    //does not store the block entities for cache invalidation reasons
    //null means inventory blockentity present (use the LithiumInventory directly), NO_INVENTORY_PRESENT means no blockentity and no composter
    private Inventory insertBlockInventory, extractBlockInventory;
    //any optimized inventories interacted with are stored (including entities) with extra data
    private LithiumInventory insertInventory, extractInventory;
    private int insertInventoryRemovedCount, extractInventoryRemovedCount;
    private LithiumStackList insertInventoryStackList, extractInventoryStackList;
    private long insertInventoryChangeCount, extractInventoryChangeCount;

    private NearbyItemEntityMovementTracker<ItemEntity> extractItemEntityTracker;
    private NearbyInventoryEntityMovementTracker<Inventory> extractInventoryEntityTracker;
    private NearbyInventoryEntityMovementTracker<Inventory> insertInventoryEntityTracker;


    @Override
    public void onNeighborUpdate(boolean above) {
        //Clear the block inventory cache (composter inventories and no inventory present) on block update / observer update
        if (above) {
            if (this.extractInventory == null) {
                this.extractBlockInventory = null;
            }
        } else {
            if (this.insertInventory == null) {
                this.insertBlockInventory = null;
            }
        }
    }

    @Redirect(method = "extract(Lnet/minecraft/world/World;Lnet/minecraft/block/entity/Hopper;)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/block/entity/HopperBlockEntity;getInputInventory(Lnet/minecraft/world/World;Lnet/minecraft/block/entity/Hopper;)Lnet/minecraft/inventory/Inventory;"))
    private static Inventory getExtractInventory(World world, Hopper hopper) {
        if (!(hopper instanceof HopperBlockEntityMixin hopperBlockEntity)) {
            return getInputInventory(world, hopper); //Hopper Minecarts do not cache Inventories
        }

        Inventory blockInventory = hopperBlockEntity.getExtractBlockInventory(world);
        if (blockInventory != null) {
            return blockInventory;
        }

        if (hopperBlockEntity.extractInventoryEntityTracker == null) {
            assert world instanceof ServerWorld;
            BlockPos pos = hopperBlockEntity.pos.offset(Direction.UP);
            hopperBlockEntity.extractInventoryEntityTracker =
                    new NearbyInventoryEntityMovementTracker<>(
                            new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1),
                            Inventory.class
                    );
            hopperBlockEntity.extractInventoryEntityTracker.register((ServerWorld) world);
        }
        long modCount = hopperBlockEntity.
                getLithiumStackList().getModCount();
        if (hopperBlockEntity.myLastCollectChangeCount == modCount &&
                hopperBlockEntity.extractInventoryEntityTracker.isUnchanged()) {
            return null;
        }
        hopperBlockEntity.myLastCollectChangeCount = modCount;

        List<Inventory> inventoryEntities = hopperBlockEntity.extractInventoryEntityTracker.getEntities();
        if (inventoryEntities.isEmpty()) {
            hopperBlockEntity.extractInventoryEntityTracker.setUnchanged(hopperBlockEntity.lastTickTime);
            //only set unchanged when no entity present. this allows shortcutting this case
            //shortcutting the entity present case requires checking its change counter
            return null;
        }
        Inventory inventory = inventoryEntities.get(world.random.nextInt(inventoryEntities.size()));
        if (inventory != hopperBlockEntity.extractInventory && inventory instanceof LithiumInventory optimizedInventory) {
            //not caching the inventory (hopperBlockEntity.extractBlockInventory == NO_INVENTORY_PRESENT prevents it)
            //make change counting on the entity inventory possible, without caching it as block inventory
            hopperBlockEntity.extractInventory = optimizedInventory;
            hopperBlockEntity.extractInventoryStackList = optimizedInventory.getLithiumStackList();
            hopperBlockEntity.extractInventoryChangeCount = hopperBlockEntity.extractInventoryStackList.getModCount() - 1;
        }
        return inventory;
    }

    /**
     * Makes this hopper remember the given inventory.
     *
     * @param insertInventory Block inventory / Blockentity inventory to be remembered
     */
    private void cacheInsertInventory(Inventory insertInventory) {
        assert !(insertInventory instanceof Entity);
        if (insertInventory instanceof BlockEntity) {
            this.insertBlockInventory = null;
        } else {
            this.insertBlockInventory = insertInventory == null ? NO_INVENTORY_BLOCK_PRESENT : insertInventory;
        }

        if (insertInventory instanceof LithiumInventory optimizedInventory) {
            this.insertInventory = optimizedInventory;
            LithiumStackList insertInventoryStackList = optimizedInventory.getLithiumStackList();
            this.insertInventoryStackList = insertInventoryStackList;
            this.insertInventoryChangeCount = insertInventoryStackList.getModCount() - 1;
            this.insertInventoryRemovedCount = optimizedInventory.getRemovedCount();
        } else {
            this.insertInventory = null;
            this.insertInventoryStackList = null;
            this.insertInventoryChangeCount = 0;
            this.insertInventoryRemovedCount = 0;
        }
    }

    public Inventory getInsertBlockInventory(World world) {
        Inventory inventory = this.insertBlockInventory;
        if (inventory != null) {
            return inventory == NO_INVENTORY_BLOCK_PRESENT ? null : inventory;
        }
        LithiumInventory optimizedInventory;
        if ((optimizedInventory = this.insertInventory) != null) {
            if (optimizedInventory.getRemovedCount() == this.insertInventoryRemovedCount) {
                return optimizedInventory;
            }
        }
        Direction direction = this.getCachedState().get(HopperBlock.FACING);
        inventory = HopperHelper.vanillaGetBlockInventory(world, this.getPos().offset(direction));
        this.cacheInsertInventory(inventory);
        return inventory;
    }

    @Redirect(
            method = "insertAndExtract",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/block/entity/HopperBlockEntity;isFull()Z"
            )
    )
    private static boolean lithiumHopperIsFull(HopperBlockEntity hopperBlockEntity) {
        //noinspection ConstantConditions
        LithiumStackList lithiumStackList = ((HopperBlockEntityMixin) (Object) hopperBlockEntity).getLithiumStackList();
        return lithiumStackList.getFullSlots() == lithiumStackList.size();
    }

    @Redirect(
            method = "insertAndExtract",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/block/entity/HopperBlockEntity;isEmpty()Z"
            )
    )
    private static boolean lithiumHopperIsEmpty(HopperBlockEntity hopperBlockEntity) {
        //noinspection ConstantConditions
        LithiumStackList lithiumStackList = ((HopperBlockEntityMixin) (Object) hopperBlockEntity).getLithiumStackList();
        return lithiumStackList.getOccupiedSlots() == 0;
    }


    @Redirect(method = "insert", at = @At(value = "INVOKE", target = "Lnet/minecraft/block/entity/HopperBlockEntity;getOutputInventory(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;)Lnet/minecraft/inventory/Inventory;"))
    private static Inventory nullify(World world, BlockPos pos, BlockState state) {
        return null;
    }

    /**
     * Effectively overwrites {@link HopperBlockEntity#insert(World, BlockPos, BlockState, Inventory)} (only usage redirect)
     * [VanillaCopy] general hopper insert logic, modified for optimizations
     * @reason Adding the inventory caching into the static method using mixins seems to be unfeasible without temporarily storing state in static fields.
     */
    @SuppressWarnings("JavadocReference")
    @Redirect(method = "insertAndExtract", at = @At(value = "INVOKE", target = "Lnet/minecraft/block/entity/HopperBlockEntity;insert(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Lnet/minecraft/inventory/Inventory;)Z"))
    private static boolean lithiumInsert(World world, BlockPos pos, BlockState state, Inventory hopper) {
        HopperBlockEntityMixin hopperBlockEntity = (HopperBlockEntityMixin) hopper;
        Inventory insertInventory = hopperBlockEntity.getInsertInventory(world);
        if (insertInventory == null) {
            //call the vanilla code, but with target inventory nullify (mixin above) to allow other mods inject features
            //e.g. carpet mod allows hoppers to insert items into wool blocks
            return insert(world, pos, state, hopper);
        }

        LithiumStackList hopperStackList = hopperBlockEntity.getLithiumStackList();
        if (hopperBlockEntity.insertInventory == insertInventory && hopperStackList.getModCount() == hopperBlockEntity.myLastInsertChangeCount) {
            if (hopperBlockEntity.insertInventoryStackList.getModCount() == hopperBlockEntity.insertInventoryChangeCount) {
//                ComparatorUpdatePattern.NO_UPDATE.apply(hopperBlockEntity, hopperStackList); //commented because it's a noop, Hoppers do not send useless comparator updates
                return false;
            }
        }

        //todo maybe should check whether the receiving inventory is not full first, like vanilla. However this is a rare shortcut case and increases the work most of the time. worst case is 5x work than with the check
        boolean insertInventoryWasEmptyHopperNotDisabled = insertInventory instanceof HopperBlockEntityMixin && !((HopperBlockEntityMixin) insertInventory).isDisabled() && hopperBlockEntity.insertInventoryStackList.getOccupiedSlots() == 0;
        Direction fromDirection = state.get(HopperBlock.FACING).getOpposite();
        int size = hopperStackList.size();
        //noinspection ForLoopReplaceableByForEach
        for (int i = 0; i < size; ++i) {
            ItemStack transferStack = hopperStackList.get(i);
            if (!transferStack.isEmpty()) {
                boolean transferSuccess = HopperHelper.tryPlaceSingleItem(insertInventory, transferStack, fromDirection);
                if (transferSuccess) {
                    transferStack.decrement(1);
                    if (insertInventoryWasEmptyHopperNotDisabled) {
                        HopperBlockEntityMixin receivingHopper = (HopperBlockEntityMixin) insertInventory;
                        int k = 8;
                        if (receivingHopper.lastTickTime >= hopperBlockEntity.lastTickTime) {
                            k = 7;
                        }
                        receivingHopper.setCooldown(k);
                    }
                    insertInventory.markDirty();
                    return true;
                }
            }
        }
        hopperBlockEntity.myLastInsertChangeCount = hopperStackList.getModCount();
        if (hopperBlockEntity.insertInventoryStackList != null) {
            hopperBlockEntity.insertInventoryChangeCount = hopperBlockEntity.insertInventoryStackList.getModCount();
        }
        return false;
    }

    public Inventory getInsertInventory(World world) {
        Inventory blockInventory = this.getInsertBlockInventory(world);
        if (blockInventory != null) {
            return blockInventory;
        }

        if (this.insertInventoryEntityTracker == null) {
            assert world instanceof ServerWorld;
            Direction direction = this.getCachedState().get(HopperBlock.FACING);
            BlockPos pos = this.pos.offset(direction);
            this.insertInventoryEntityTracker =
                    new NearbyInventoryEntityMovementTracker<>(
                            new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1),
                            Inventory.class
                    );
            this.insertInventoryEntityTracker.register((ServerWorld) world);
        }
        long modCount = this.
                getLithiumStackList().getModCount();
        if (this.myLastCollectChangeCount == modCount &&
                this.insertInventoryEntityTracker.isUnchanged()) {
            return null;
        }
        this.myLastCollectChangeCount = modCount;

        List<Inventory> inventoryEntities = this.insertInventoryEntityTracker.getEntities();
        if (inventoryEntities.isEmpty()) {
            this.insertInventoryEntityTracker.setUnchanged(this.lastTickTime);
            //only set unchanged when no entity present. this allows shortcutting this case
            //shortcutting the entity present case requires checking its change counter
            return null;
        }
        Inventory inventory = inventoryEntities.get(world.random.nextInt(inventoryEntities.size()));
        if (inventory != this.insertInventory && inventory instanceof LithiumInventory optimizedInventory) {
            //not caching the inventory (this.insertBlockInventory == NO_INVENTORY_PRESENT prevents it)
            //make change counting on the entity inventory possible, without caching it as block inventory
            this.insertInventory = optimizedInventory;
            this.insertInventoryStackList = optimizedInventory.getLithiumStackList();
            this.insertInventoryChangeCount = this.insertInventoryStackList.getModCount() - 1;
        }
        return inventory;
    }

    /**
     * Makes this hopper remember the given inventory.
     *
     * @param extractInventory Block inventory / Blockentity inventory to be remembered
     */
    private void cacheExtractInventory(Inventory extractInventory) {
        assert !(extractInventory instanceof Entity);
        if (extractInventory instanceof BlockEntity) {
            this.extractBlockInventory = null;
        } else {
            this.extractBlockInventory = extractInventory == null ? NO_INVENTORY_BLOCK_PRESENT : extractInventory;
        }
        if (extractInventory instanceof LithiumInventory optimizedInventory) {
            this.extractInventory = optimizedInventory;
            LithiumStackList extractInventoryStackList = optimizedInventory.getLithiumStackList();
            this.extractInventoryStackList = extractInventoryStackList;
            this.extractInventoryChangeCount = extractInventoryStackList.getModCount() - 1;
            this.extractInventoryRemovedCount = optimizedInventory.getRemovedCount();
        } else {
            this.extractInventory = null;
            this.extractInventoryStackList = null;
            this.extractInventoryChangeCount = 0;
            this.extractInventoryRemovedCount = 0;
        }
    }

    public Inventory getExtractBlockInventory(World world) {
        Inventory inventory = this.extractBlockInventory;
        if (inventory != null) {
            return inventory == NO_INVENTORY_BLOCK_PRESENT ? null : inventory;
        }
        LithiumInventory optimizedInventory;
        if ((optimizedInventory = this.extractInventory) != null) {
            if (optimizedInventory.getRemovedCount() == this.extractInventoryRemovedCount) {
                return optimizedInventory;
            }
        }
        inventory = HopperHelper.vanillaGetBlockInventory(world, this.getPos().up());
        this.cacheExtractInventory(inventory);
        return inventory;
    }

    /**
     * Inject to replace the extract method with an optimized but equivalent replacement.
     * Uses the vanilla method as fallback for non-optimized Inventories.
     * @param to Hopper or Hopper Minecart that is extracting
     * @param from Inventory the hopper is extracting from
     */
    @Inject(method = "extract(Lnet/minecraft/world/World;Lnet/minecraft/block/entity/Hopper;)Z", at = @At(value = "FIELD", target = "Lnet/minecraft/util/math/Direction;DOWN:Lnet/minecraft/util/math/Direction;", shift = At.Shift.AFTER), cancellable = true, locals = LocalCapture.CAPTURE_FAILHARD)
    private static void lithiumExtract(World world, Hopper to, CallbackInfoReturnable<Boolean> cir, Inventory from) {
        if (to instanceof HopperMinecartEntity) {
            return; //optimizations not implemented for hopper minecarts
        }
        HopperBlockEntityMixin hopperBlockEntity = (HopperBlockEntityMixin) to;
        if (from != hopperBlockEntity.extractInventory) {
            return; //from inventory is not an optimized inventory, vanilla fallback
        }

        LithiumStackList hopperStackList = hopperBlockEntity.getLithiumStackList();
        LithiumStackList fromStackList = hopperBlockEntity.extractInventoryStackList;

        if (hopperStackList.getModCount() == hopperBlockEntity.myLastExtractChangeCount) {
            if (fromStackList.getModCount() == hopperBlockEntity.extractInventoryChangeCount) {
                //noinspection CollectionAddedToSelf
                fromStackList.runComparatorUpdatePatternOnFailedExtract(fromStackList, from);
                cir.setReturnValue(false);
                return;
            }
        }

        int[] availableSlots = from instanceof SidedInventory ? ((SidedInventory) from).getAvailableSlots(Direction.DOWN) : null;
        int fromSize = availableSlots != null ? availableSlots.length : from.size();
        for (int i = 0; i < fromSize; i++) {
            int fromSlot = availableSlots != null ? availableSlots[i] : i;
            ItemStack itemStack = fromStackList.get(fromSlot);
            if (!itemStack.isEmpty() && canExtract(from, itemStack, fromSlot, Direction.DOWN)) {
                //calling removeStack is necessary due to its side effects (markDirty in LootableContainerBlockEntity)
                ItemStack takenItem = from.removeStack(fromSlot, 1);
                boolean transferSuccess = HopperHelper.tryPlaceSingleItem(to, takenItem, null);
                if (transferSuccess) {
                    from.markDirty();
                    cir.setReturnValue(true);
                    return;
                }
                //put the item back similar to vanilla
                ItemStack restoredStack = fromStackList.get(fromSlot);
                if (restoredStack.isEmpty()) {
                    restoredStack = takenItem;
                } else {
                    restoredStack.increment(1);
                }
                //calling setStack is necessary due to its side effects (markDirty in LootableContainerBlockEntity)
                from.setStack(fromSlot, restoredStack);
            }
        }
        hopperBlockEntity.myLastExtractChangeCount = hopperStackList.getModCount();
        if (fromStackList != null) {
            hopperBlockEntity.extractInventoryChangeCount = fromStackList.getModCount();
        }
        cir.setReturnValue(false);
    }

    @Redirect(method = "extract(Lnet/minecraft/world/World;Lnet/minecraft/block/entity/Hopper;)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/block/entity/HopperBlockEntity;getInputItemEntities(Lnet/minecraft/world/World;Lnet/minecraft/block/entity/Hopper;)Ljava/util/List;"))
    private static List<ItemEntity> lithiumGetInputItemEntities(World world, Hopper hopper) {
        if (!(hopper instanceof HopperBlockEntityMixin hopperBlockEntity)) {
            return getInputItemEntities(world, hopper); //optimizations not implemented for hopper minecarts
        }
        
        if (hopperBlockEntity.extractItemEntityTracker == null) {
            assert world instanceof ServerWorld;
            hopperBlockEntity.initExtractItemEntityTracker((ServerWorld) world);
        }
        long modCount = hopperBlockEntity.getLithiumStackList().getModCount();
        if (hopperBlockEntity.myLastCollectChangeCount == modCount &&
                hopperBlockEntity.extractItemEntityTracker.isUnchanged()) {
            return Collections.emptyList();
        }
        hopperBlockEntity.myLastCollectChangeCount = modCount;

        List<ItemEntity> itemEntities = hopperBlockEntity.extractItemEntityTracker.getEntities();
        hopperBlockEntity.extractItemEntityTracker.setUnchanged(hopperBlockEntity.lastTickTime);
        //set unchanged so that if this extract fails and there is no other change to hoppers or items, extracting
        // items can be skipped.
        return itemEntities;
    }
    
    private void initExtractItemEntityTracker(ServerWorld serverWorld) {
        List<Box> list = new ArrayList<>();
        Box encompassingBox = null;
        for (Box box : this.getInputAreaShape().getBoundingBoxes()) {
            Box offsetBox = box.offset(this.pos.getX(), this.pos.getY(), this.pos.getZ());
            list.add(offsetBox);
            if (encompassingBox == null) {
                encompassingBox = offsetBox;
            } else {
                encompassingBox = encompassingBox.union(offsetBox);
            }
        }
        this.extractItemEntityTracker =
                new NearbyItemEntityMovementTracker<>(
                        encompassingBox,
                        list.toArray(new Box[0]),
                        ItemEntity.class
                );
        this.extractItemEntityTracker.register((ServerWorld) world);
    }

    @Override
    public void markRemoved() {
        super.markRemoved();
        if (this.world instanceof ServerWorld serverWorld) {
            if (this.insertInventoryEntityTracker != null) {
                this.insertInventoryEntityTracker.unRegister(serverWorld);
            }
            if (this.extractInventoryEntityTracker != null) {
                this.extractInventoryEntityTracker.unRegister(serverWorld);
            }
            if (this.extractItemEntityTracker != null) {
                this.extractItemEntityTracker.unRegister(serverWorld);
            }
        }

        this.insertBlockInventory = null;
        this.extractBlockInventory = null;
        this.insertInventory = null;
        this.extractInventory = null;
        this.insertInventoryRemovedCount = 0;
        this.extractInventoryRemovedCount = 0;
        this.insertInventoryStackList = null;
        this.extractInventoryStackList = null;
        this.insertInventoryChangeCount = 0;
        this.extractInventoryChangeCount = 0;
    }

    /**
     * @author 2No2Name
     * @reason avoid stream code
     */
    @Overwrite
    private static boolean isInventoryEmpty(Inventory inv, Direction side) {
        int[] availableSlots = inv instanceof SidedInventory ? ((SidedInventory) inv).getAvailableSlots(side) : null;
        int fromSize = availableSlots != null ? availableSlots.length : inv.size();
        for (int i = 0; i < fromSize; i++) {
            int fromSlot = availableSlots != null ? availableSlots[i] : i;
            if (!inv.getStack(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}