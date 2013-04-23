#ifndef _DELITEARRAY_H_
#define _DELITEARRAY_H_

#include "DeliteCuda.h"

#include <stdlib.h>
#include <assert.h>

template <class T>
class DeliteArray {
public:
    T *data;
    int length;
    int offset;
    int stride;
    int flag;

    // Constructor
    __host__ __device__ DeliteArray(void) {
      length = 0;
      data = NULL;
    }

    /*
    __device__ DeliteArray(int _length, T *basePtr) {
      //int idx = threadIdx.x + blockIdx.x*blockDim.x;
      length = _length;
      //data = basePtr + idx*_length;
      data = basePtr;
    }
    */

    __host__ DeliteArray(int _length) {
        length = _length;
        DeliteCudaMalloc((void**)&data,length*sizeof(T));
        offset = 0;
        stride = 1;
        flag = 1;
    }

    __host__ __device__ DeliteArray(int _length, T *_data, int _offset) {
        length = _length;
        data = _data; // + _offset * _length;
        offset = _offset *_length;
        stride = 1;
        flag = 1;
    }

    __host__ __device__ DeliteArray(int _length, T *_data, int _offset, int _stride) {
        length = _length;
        data = _data; // + _offset*_length;
        offset = _offset;
        stride = _stride;
        flag = 1;
    }

    __host__ __device__ T apply(int idx) {
      if(flag!=1) 
        return data[offset + (idx % flag) * stride + idx / flag];
      else 
        return data[offset + idx * stride];
        //return data[idx];
    }

    __host__ __device__ void update(int idx, T value) {
      if(flag!=1) 
        data[offset + (idx % flag) * stride + idx / flag] = value;
      else
        data[offset + idx * stride] = value;
        //data[idx] = value;
    }

    // DeliteCoolection
    __host__ __device__ int size() {
        return length;
    }

    __host__ __device__ T dc_apply(int idx) {
        return apply(idx);
    }

    __host__ __device__ void dc_update(int idx, T value) {
        update(idx,value);
    }

    __host__ __device__ void dc_copy(DeliteArray<T> from) {
      for(int i=0; i<length; i++)
        update(i,from.apply(i));
    }

    __host__ DeliteArray<T> *dc_alloc(void) {
      return new DeliteArray<T>(length);
    }

    __host__ DeliteArray<T> *dc_alloc(int size) {
       return new DeliteArray<T>(size);
    }

    
};

#endif
