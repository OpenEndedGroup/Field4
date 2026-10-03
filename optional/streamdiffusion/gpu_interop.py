"""Vulkan opaque-FD allocations mapped into the worker's existing Torch CUDA context."""
import os
import torch
from cuda import cuda


def checked(result):
    error, *values = result
    if error != cuda.CUresult.CUDA_SUCCESS:
        raise RuntimeError(f"CUDA driver: {error}")
    return values[0] if len(values) == 1 else values


def select_device(uuid):
    checked(cuda.cuInit(0))
    for index in range(checked(cuda.cuDeviceGetCount())):
        device = checked(cuda.cuDeviceGet(index))
        if checked(cuda.cuDeviceGetUuid(device)).bytes.hex() == uuid:
            torch.cuda.set_device(index)
            torch.empty(0, device=f"cuda:{index}")  # establish PyTorch's primary context
            return index
    raise RuntimeError(f"No CUDA device matches the Field Vulkan device UUID {uuid}")


class SharedSlot:
    def __init__(self, descriptor, memory_fd, semaphore_fd, width, height, device, unconsumed):
        self.memory = self.pointer = self.semaphore = self.tensor = None
        self.motion_tensor = None
        self.value = 0
        self.width, self.height = width, height
        try:
            d = cuda.CUDA_EXTERNAL_MEMORY_HANDLE_DESC()
            d.type = cuda.CUexternalMemoryHandleType.CU_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD
            d.handle.fd = memory_fd
            d.size = descriptor["size"]
            d.flags = 1 if descriptor["dedicated"] else 0
            self.memory = checked(cuda.cuImportExternalMemory(d))
            unconsumed.remove(memory_fd)  # CUDA now owns the received duplicate
            mapping = cuda.CUDA_EXTERNAL_MEMORY_BUFFER_DESC()
            mapping.offset, mapping.size = 0, descriptor["size"]
            self.pointer = checked(cuda.cuExternalMemoryGetMappedBuffer(self.memory, mapping))
            sd = cuda.CUDA_EXTERNAL_SEMAPHORE_HANDLE_DESC()
            sd.type = cuda.CUexternalSemaphoreHandleType.CU_EXTERNAL_SEMAPHORE_HANDLE_TYPE_TIMELINE_SEMAPHORE_FD
            sd.handle.fd = semaphore_fd
            self.semaphore = checked(cuda.cuImportExternalSemaphore(sd))
            unconsumed.remove(semaphore_fd)
            motion_offset = (width * height * 4 + 15) & ~15
            expected_bytes = motion_offset + width * height * 16 if descriptor.get("motion", False) else width * height * 4
            if descriptor["bytes"] != expected_bytes or descriptor["size"] < descriptor["bytes"]:
                raise ValueError("Invalid shared GPU buffer extent")
            self.__cuda_array_interface__ = {
                "shape": (height, width, 4), "strides": None, "typestr": "|u1",
                "data": (int(self.pointer), False), "version": 3,
            }
            self.tensor = torch.as_tensor(self, device=f"cuda:{device}")
            if descriptor.get("motion", False):
                class MotionAlias:
                    __cuda_array_interface__ = {
                        "shape": (height, width, 4), "strides": None, "typestr": "<f4",
                        "data": (int(self.pointer) + motion_offset, False), "version": 3,
                    }
                self.motion_tensor = torch.as_tensor(MotionAlias(), device=f"cuda:{device}")
                if self.motion_tensor.data_ptr() != int(self.pointer) + motion_offset:
                    raise RuntimeError("Torch did not alias the imported motion allocation")
            if self.tensor.data_ptr() != int(self.pointer):
                raise RuntimeError("Torch did not alias the imported CUDA allocation")
        except BaseException:
            self.close()
            raise

    def wait(self, value):
        params = cuda.CUDA_EXTERNAL_SEMAPHORE_WAIT_PARAMS()
        params.params.fence.value = value
        checked(cuda.cuWaitExternalSemaphoresAsync([self.semaphore], [params], 1,
            torch.cuda.current_stream().cuda_stream))

    def signal(self, value):
        params = cuda.CUDA_EXTERNAL_SEMAPHORE_SIGNAL_PARAMS()
        params.params.fence.value = value
        checked(cuda.cuSignalExternalSemaphoresAsync([self.semaphore], [params], 1,
            torch.cuda.current_stream().cuda_stream))
        self.value = value

    def close(self):
        self.tensor = self.motion_tensor = None
        if self.pointer is not None:
            checked(cuda.cuMemFree(self.pointer))
            self.pointer = None
        if self.semaphore is not None:
            checked(cuda.cuDestroyExternalSemaphore(self.semaphore))
            self.semaphore = None
        if self.memory is not None:
            checked(cuda.cuDestroyExternalMemory(self.memory))
            self.memory = None


def completed():
    """Host observes completion of metadata ownership changes; no pixel transfer."""
    event = torch.cuda.Event()
    event.record(torch.cuda.current_stream())
    event.synchronize()


def release_unconsumed(fds):
    for fd in fds:
        os.close(fd)
